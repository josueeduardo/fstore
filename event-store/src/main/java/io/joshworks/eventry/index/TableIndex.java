package io.joshworks.eventry.index;

import io.joshworks.eventry.StreamName;
import io.joshworks.eventry.index.disk.IndexAppender;
import io.joshworks.eventry.stream.StreamMetadata;
import io.joshworks.fstore.core.io.IOUtils;
import io.joshworks.fstore.core.util.Threads;
import io.joshworks.fstore.log.Direction;
import io.joshworks.fstore.log.iterators.Iterators;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

public class TableIndex implements Closeable {

    private static final Logger logger = LoggerFactory.getLogger(TableIndex.class);
    public static final int DEFAULT_FLUSH_THRESHOLD = 1000000;
    public static final boolean DEFAULT_USE_COMPRESSION = false;
    public static final int DEFAULT_WRITE_QUEUE_SIZE = 5;
    private final int flushThreshold; //TODO externalize

    private final IndexAppender diskIndex;
    private final List<MemIndex> writeQueue;
    private final ExecutorService indexWriter = Executors.newSingleThreadExecutor(Threads.namedThreadFactory("index-writer"));
    private final AtomicBoolean closed = new AtomicBoolean();
    private final int maxWriteQueueSize;

    private final Set<SingleIndexIterator> pollers = new HashSet<>();
    private final Consumer<FlushInfo> indexFlushListener;

    private MemIndex memIndex = new MemIndex();

    public TableIndex(File rootDirectory, Function<Long, StreamMetadata> streamSupplier, Consumer<FlushInfo> indexFlushListener) {
        this(rootDirectory, streamSupplier, indexFlushListener, DEFAULT_FLUSH_THRESHOLD, DEFAULT_WRITE_QUEUE_SIZE, DEFAULT_USE_COMPRESSION);
    }

    public TableIndex(File rootDirectory, Function<Long, StreamMetadata> streamSupplier, Consumer<FlushInfo> indexFlushListener, int flushThreshold, int maxWriteQueueSize, boolean useCompression) {
        this.maxWriteQueueSize = maxWriteQueueSize;
        this.diskIndex = new IndexAppender(rootDirectory, streamSupplier, flushThreshold, useCompression);
        this.flushThreshold = flushThreshold;
        this.indexFlushListener = indexFlushListener;
        this.writeQueue = new CopyOnWriteArrayList<>();
        this.indexWriter.execute(this::writeIndex);

    }

    public void add(StreamName stream, long position) {
        add(stream.hash(), stream.version(), position);
    }

    //returns true if flushed to disk
    public void add(long stream, int version, long position) {
        if (version <= IndexEntry.NO_VERSION) {
            throw new IllegalArgumentException("Version must be greater than or equals to zero");
        }
        if (position < 0) {
            throw new IllegalArgumentException("Position must be greater than zero");
        }
        IndexEntry entry = IndexEntry.of(stream, version, position);
        memIndex.add(entry);

        if (memIndex.size() >= flushThreshold) {
            flushAsync();
        }
    }

    public int version(long stream) {
        Iterator<MemIndex> it = memIndices(Direction.BACKWARD);
        while (it.hasNext()) {
            MemIndex index = it.next();
            int version = index.version(stream);
            if (version > IndexEntry.NO_VERSION) {
                return version;
            }
        }
        return diskIndex.version(stream);
    }


    public long size() {
        return diskIndex.entries() + memIndex.size();
    }


    public IndexIterator indexedIterator(Checkpoint checkpoint) {
        if (checkpoint.size() > 1) {
            return indexedIterator(checkpoint, false);
        }
        long stream = checkpoint.keySet().iterator().next();
        int lastVersion = checkpoint.get(stream);
        return new SingleIndexIterator(diskIndex, this::memIndices, Direction.FORWARD, stream, lastVersion);
    }

    //TODO: IMPLEMENT BACKWARD SCANNING: Backwards scan requires fetching the latest version and adding to the map
    //backward here means that the version will be fetched from higher to lower
    //no guarantees of the order of the streams
    public IndexIterator indexedIterator(Checkpoint checkpoint, boolean ordered) {
        if (checkpoint.size() <= 1) {
            return indexedIterator(checkpoint);
        }

        int maxEntries = 1000000; //Useful only for ordered to prevent OOM. configurable ?
        int maxEntriesPerStream = ordered ? checkpoint.size() / maxEntries : -1;
        List<IndexIterator> iterators = checkpoint.entrySet()
                .stream()
                .map(kv -> new SingleIndexIterator(diskIndex, this::memIndices, Direction.FORWARD, kv.getKey(), kv.getValue(), maxEntriesPerStream))
                .collect(Collectors.toList());

        return new MultiStreamIndexIterator(iterators, ordered);
    }

    public Optional<IndexEntry> get(long stream, int version) {
        Iterator<MemIndex> it = memIndices(Direction.BACKWARD);
        while (it.hasNext()) {
            MemIndex index = it.next();
            Optional<IndexEntry> fromMemory = index.get(stream, version);
            if (fromMemory.isPresent()) {
                return fromMemory;
            }
        }
        return diskIndex.get(stream, version);
    }

    private void writeIndex() {
        try {
            while (!closed.get()) {
                if (writeQueue.isEmpty()) {
                    Threads.sleep(200);
                    continue;
                }
                logger.info("Writing index to disk");

                long start = System.currentTimeMillis();
                MemIndex index = writeQueue.get(0);
                diskIndex.writeToDisk(index);
                writeQueue.remove(0);
                long timeTaken = System.currentTimeMillis() - start;
                logger.info("Index write took {}ms", timeTaken);
                indexFlushListener.accept(new FlushInfo(index.size(), timeTaken));
            }

        } catch (Exception e) {
            throw new IllegalStateException("Failed write index", e);
        }
    }

    //Adds a job to the queue
    public void flushAsync() {
        try {
            logger.info("Adding mem index to write queue");
            while (writeQueue.size() > maxWriteQueueSize) {
                Threads.sleep(100);
            }
            writeQueue.add(memIndex);
            memIndex = new MemIndex();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to add mem index to write queue");
        }
    }

    public void compact() {
        diskIndex.compact();
    }

    private Iterator<MemIndex> memIndices(Direction direction) {
        List<MemIndex> indices = new ArrayList<>(writeQueue);
        indices.add(memIndex);

        return Direction.FORWARD.equals(direction) ? indices.iterator() : Iterators.reversed(indices);

    }

    @Override
    public void close() {
//        this.flush(); //no need to flush, just reload from disk on startup
        if (closed.compareAndSet(false, true)) {
            Threads.awaitTerminationOf(indexWriter, 2, TimeUnit.SECONDS, () -> logger.info("Awaiting index writer to complete"));
            diskIndex.close();
            for (SingleIndexIterator poller : pollers) {
                IOUtils.closeQuietly(poller);
            }
            pollers.clear();
        }
    }

    public class FlushInfo {
        public int entries;
        public final long timeTaken;

        private FlushInfo(int entries, long timeTaken) {
            this.entries = entries;
            this.timeTaken = timeTaken;
        }
    }
}