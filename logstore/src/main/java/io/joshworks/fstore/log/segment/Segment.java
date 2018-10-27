package io.joshworks.fstore.log.segment;

import io.joshworks.fstore.core.RuntimeIOException;
import io.joshworks.fstore.core.Serializer;
import io.joshworks.fstore.core.io.IOUtils;
import io.joshworks.fstore.core.io.Storage;
import io.joshworks.fstore.log.Direction;
import io.joshworks.fstore.log.Iterators;
import io.joshworks.fstore.log.LogIterator;
import io.joshworks.fstore.log.PollingSubscriber;
import io.joshworks.fstore.log.TimeoutReader;
import io.joshworks.fstore.log.record.BufferRef;
import io.joshworks.fstore.log.record.DataStream;
import io.joshworks.fstore.log.record.IDataStream;
import io.joshworks.fstore.log.segment.header.LogHeader;
import io.joshworks.fstore.log.segment.header.Type;
import io.joshworks.fstore.log.utils.Logging;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static java.util.Objects.requireNonNull;

/**
 * File format:
 * <p>
 * |---- HEADER ----|----- LOG -----|--- END OF LOG (8bytes) ---|
 */
public class Segment<T> implements Log<T> {

    private final Logger logger;

    private final Serializer<T> serializer;
    private final Storage storage;
    private final IDataStream dataStream;
    private final String magic;

    private AtomicLong entries = new AtomicLong();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean markedForDeletion = new AtomicBoolean();

    private LogHeader header;

    private final Object READER_LOCK = new Object();
    private final Set<TimeoutReader> readers = ConcurrentHashMap.newKeySet();

    //Type is only used for new segments, accepted values are Type.LOG_HEAD or Type.MERGE_OUT
    //Magic is used to create new segment or verify existing
    public Segment(Storage storage, Serializer<T> serializer, IDataStream dataStream, String magic, Type type) {
        try {
            this.serializer = requireNonNull(serializer, "Serializer must be provided");
            this.storage = requireNonNull(storage, "Storage must be provided");
            this.dataStream = requireNonNull(dataStream, "Reader must be provided");
            this.magic = requireNonNull(magic, "Magic must be provided");
            this.logger = Logging.namedLogger(storage.name(), "segment");

            LogHeader foundHeader = LogHeader.read(storage);
            if (foundHeader == null) {
                if (type == null) {
                    throw new SegmentException("Segment doesn't exist, " + Type.LOG_HEAD + " or " + Type.MERGE_OUT + " must be specified");
                }
                this.header = LogHeader.writeNew(storage, magic, type, storage.length());

                this.position(Log.START);
                this.entries.set(this.header.entries);

            } else {
                this.header = foundHeader;
                SegmentState result = rebuildState(Segment.START);
                this.position(result.position);
                this.entries.set(result.entries);
            }
            LogHeader.validateMagic(this.header.magic, magic);

        } catch (Exception e) {
            IOUtils.closeQuietly(storage);
            throw new SegmentException("Failed to construct segment", e);
        }
    }

    private void position(long position) {
        if (position < START) {
            throw new IllegalArgumentException("Position must be at least " + LogHeader.BYTES);
        }
        this.storage.position(position);
    }

    @Override
    public long append(T data) {
        if (readOnly()) {
            throw new IllegalStateException("Segment is read only");
        }
        ByteBuffer bytes = serializer.toBytes(data);
        long position = position();
        if (!hasAvailableSpace(bytes, position)) {
            return -1;
        }
        long recordPosition = dataStream.write(storage, bytes);
        entries.incrementAndGet();
        return recordPosition;
    }

    private boolean hasAvailableSpace(ByteBuffer bytes, long position) {
        return LogHeader.BYTES + Log.EOL.length + position + bytes.remaining() <= header.fileSize;
    }

    @Override
    public T get(long position) {
        checkBounds(position);
        try (BufferRef ref = dataStream.read(storage, Direction.FORWARD, position)) {
            ByteBuffer bb = ref.get();
            if (bb.remaining() == 0) { //EOF
                return null;
            }
            return serializer.fromBytes(bb);
        }
    }

    @Override
    public PollingSubscriber<T> poller(long position) {
        checkClosed();
        SegmentPoller segmentPoller = new SegmentPoller(dataStream, serializer, position);
        return acquireReader(segmentPoller);
    }

    @Override
    public PollingSubscriber<T> poller() {
        return poller(Log.START);
    }

    private void checkBounds(long position) {
        if (position < START) {
            throw new IllegalArgumentException("Position must be greater or equals to " + START + ", got: " + position);
        }
        long logicalSize = logicalSize();
        if (position > logicalSize) {
            throw new IllegalArgumentException("Position must be less than logicalSize " + logicalSize + ", got " + position);
        }
    }

    @Override
    public long fileSize() {
        return storage.length();
    }

    @Override
    public long logicalSize() {
        return readOnly() ? header.logicalSize : position();
    }

    @Override
    public Set<TimeoutReader> readers() {
        return readers;
    }

    @Override
    public String name() {
        return storage.name();
    }

    @Override
    public Stream<T> stream(Direction direction) {
        return Iterators.closeableStream(iterator(direction));
    }

    @Override
    public LogIterator<T> iterator(Direction direction) {
        if (Direction.FORWARD.equals(direction)) {
            return iterator(START, direction);
        }
        long startPos = readOnly() ? header.logicalSize : position();
        return iterator(startPos, direction);

    }

    @Override
    public LogIterator<T> iterator(long position, Direction direction) {
        checkClosed();
        return newLogReader(position, direction);
    }

    @Override
    public long position() {
        return storage.position();
    }

    @Override
    public void close() {
        synchronized (READER_LOCK) {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            readers.clear(); //evict readers
            IOUtils.closeQuietly(storage);
        }
    }

    @Override
    public void flush() {
        try {
            storage.flush();
        } catch (IOException e) {
            throw RuntimeIOException.of(e);
        }
    }

    @Override
    public SegmentState rebuildState(long lastKnownPosition) {
        if (lastKnownPosition < START) {
            throw new IllegalStateException("Invalid lastKnownPosition: " + lastKnownPosition + ",value must be at least " + START);
        }
        long position = lastKnownPosition;
        int foundEntries = 0;
        long start = System.currentTimeMillis();
        try {
            logger.info("Restoring log state and checking consistency from position {}", lastKnownPosition);
            int lastRead;
            do {
                try (BufferRef ref = dataStream.bulkRead(storage, Direction.FORWARD, position)) {
                    List<T> items = new ArrayList<>();
                    int[] recordSizes = ref.readAllInto(items, serializer);
                    if (!items.isEmpty()) {
                        foundEntries += processEntries(items);
                    }
                    lastRead = IntStream.of(recordSizes).sum();
                    position += lastRead;
                }
            } while (lastRead > 0);

        } catch (Exception e) {
            logger.warn("Found inconsistent entry on position {}, segment '{}': {}", position, name(), e.getMessage());
            storage.position(position);
            dataStream.write(storage, ByteBuffer.wrap(EOL));
            storage.position(position);
        }
        logger.info("Log state restored in {}ms, current position: {}, entries: {}", (System.currentTimeMillis() - start), position, foundEntries);
        if (position < Log.START) {
            throw new IllegalStateException("Initial log state position must be at least " + Log.START);
        }
        return new SegmentState(foundEntries, position);
    }

    protected long processEntries(List<T> items) {
        return items.size();
    }

    @Override
    public void delete() {
        synchronized (READER_LOCK) {
            if (!markedForDeletion.compareAndSet(false, true)) {
                return;
            }
            LogHeader.writeDeleted(storage, this.header);
            if (readers.isEmpty()) {
                deleteInternal();
            } else {
                logger.info("{} active readers while deleting, marked for deletion", readers.size());
            }

        }
    }

    private void deleteInternal() {
        String name = name();
        logger.info("Deleting {}", name);
        storage.delete();
        close();
        logger.info("Deleted {}", name);
    }

    @Override
    public void roll(int level) {
        if (readOnly()) {
            throw new IllegalStateException("Cannot roll read only segment: " + this.toString());
        }

        long currPos = storage.position();
        writeEndOfLog();
        this.header = LogHeader.writeCompleted(storage, this.header, entries.get(), level, currPos);

    }

    private <R extends TimeoutReader> R acquireReader(R reader) {
        synchronized (READER_LOCK) {
            if (markedForDeletion.get() || closed.get()) {
                throw new RuntimeException("Could not acquire reader");
            }
            readers.add(reader);
            return reader;
        }
    }

    private <R extends TimeoutReader> void removeFromReaders(R reader) {
        synchronized (READER_LOCK) {
            boolean removed = readers.remove(reader);
            if (removed) { //may be called multiple times for the same reader
                if (markedForDeletion.get() && readers.isEmpty()) {
                    deleteInternal();
                }
            }
        }
    }

    private void writeEndOfLog() {
        storage.write(ByteBuffer.wrap(Log.EOL));
    }

    //TODO implement race condition on acquiring readers and closing / deleting segment
    //ideally the delete functionality would be moved to inside the segment, instead handling in the Compactor
    private SegmentReader newLogReader(long pos, Direction direction) {
        checkClosed();
        checkBounds(pos);
        long logEnd = Direction.FORWARD.equals(direction) ? logicalSize() : Log.START;
        SegmentReader segmentReader = new SegmentReader(dataStream, serializer, pos, logEnd, direction);
        return acquireReader(segmentReader);
    }

    @Override
    public boolean readOnly() {
        return Type.READ_ONLY.equals(header.type);
    }

    @Override
    public long entries() {
        return entries.get();
    }

    @Override
    public int level() {
        return header.level;
    }

    @Override
    public long created() {
        return header.created;
    }

    @Override
    public Type type() {
        return header.type;
    }


    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Segment<?> segment = (Segment<?>) o;
        return Objects.equals(storage, segment.storage) &&
                Objects.equals(magic, segment.magic);
    }

    @Override
    public int hashCode() {
        return Objects.hash(storage, magic);
    }

    @Override
    public String toString() {
        return "LogSegment{" + "handler=" + storage.name() +
                ", entries=" + entries +
                ", header=" + header +
                ", readers=" + Arrays.toString(readers.toArray()) +
                '}';
    }

    private void checkClosed() {
        if (closed.get()) {
            throw new SegmentClosedException("Segment " + name() + "is closed");
        }
    }

    private class SegmentReader extends TimeoutReader implements LogIterator<T> {

        private final IDataStream dataStream;
        private final Serializer<T> serializer;
        private final long logEnd;
        private final Direction direction;
        private final Queue<T> pageQueue = new ArrayDeque<>(DataStream.MAX_BULK_READ_RESULT);
        private final Queue<Integer> entriesSizes = new ArrayDeque<>(DataStream.MAX_BULK_READ_RESULT);

        protected long position;
        private long readAheadPosition;

        SegmentReader(IDataStream dataStream, Serializer<T> serializer, long initialPosition, long logEnd, Direction direction) {
            this.dataStream = dataStream;
            this.logEnd = logEnd;
            this.direction = direction;
            this.serializer = serializer;
            this.position = initialPosition;
            this.readAheadPosition = initialPosition;
            this.lastReadTs = System.currentTimeMillis();
            readAhead();
        }

        @Override
        public long position() {
            return position;
        }

        @Override
        public boolean hasNext() {
            return !pageQueue.isEmpty();
        }

        @Override
        public T next() {
            if (pageQueue.isEmpty()) {
                close();
                throw new NoSuchElementException();
            }
            lastReadTs = System.currentTimeMillis();

            T current = pageQueue.poll();
            int recordSize = entriesSizes.poll();
            if (pageQueue.isEmpty()) {
                readAhead();
                if (pageQueue.isEmpty()) {
                    close();
                }
            }

            position = Direction.FORWARD.equals(direction) ? position + recordSize : position - recordSize;
            return current;
        }

        private void readAhead() {
            if (Segment.this.closed.get()) {
                throw new RuntimeException("Closed segment");
            }
            if (Direction.FORWARD.equals(direction) && readAheadPosition >= logEnd) {
                return;
            }
            if (Direction.BACKWARD.equals(direction) && readAheadPosition <= logEnd) {
                return;
            }
            int totalRead = 0;
            try (BufferRef ref = dataStream.bulkRead(storage, direction, readAheadPosition)) {
                int[] entriesLength = ref.readAllInto(pageQueue, serializer);
                for (int length : entriesLength) {
                    entriesSizes.add(length);
                    totalRead += length;
                }

                if (entriesLength.length == 0) {
                    return;
                }
                readAheadPosition = Direction.FORWARD.equals(direction) ? readAheadPosition + totalRead : readAheadPosition - totalRead;
            }

        }

        @Override
        public void close() {
            Segment.this.removeFromReaders(this);
        }

        @Override
        public String toString() {
            return "SegmentReader{ readPosition=" + readAheadPosition +
                    ", order=" + direction +
                    ", readAheadPosition=" + readAheadPosition +
                    ", lastReadTs=" + lastReadTs +
                    '}';
        }
    }

    private class SegmentPoller extends TimeoutReader implements PollingSubscriber<T> {

        private static final int POLL_MS = 500;

        private final IDataStream dataStream;
        private final Serializer<T> serializer;
        private final Queue<T> pageQueue = new LinkedList<>();
        private final Queue<Integer> entriesSizes =  new ArrayDeque<>(DataStream.MAX_BULK_READ_RESULT);
        private long readPosition;

        SegmentPoller(IDataStream dataStream, Serializer<T> serializer, long initialPosition) {
            checkBounds(initialPosition);
            this.dataStream = dataStream;
            this.serializer = serializer;
            this.readPosition = initialPosition;
            this.lastReadTs = System.currentTimeMillis();
        }

        private T read(boolean advance) {
            if (Segment.this.closed.get()) {
                close();
                throw new RuntimeException("Closed segment");
            }
            T val = readCached(advance);
            if (val != null) {
                return val;
            }

            try (BufferRef ref = dataStream.bulkRead(storage, Direction.FORWARD, readPosition)) {
                int[] entriesLength = ref.readAllInto(pageQueue, serializer);
                for (int length : entriesLength) {
                    entriesSizes.add(length);
                }

                return entriesLength.length > 0 ? readCached(advance) : null;
            }
        }

        private T readCached(boolean advance) {
            T val = advance ? pageQueue.poll() : pageQueue.peek();
            if (val != null) {
                Integer len = advance ? entriesSizes.poll() : entriesSizes.peek();
                if (len == null) {
                    throw new IllegalStateException("No length available for entry");
                }
                if (advance) {
                    readPosition += len;
                }
            }
            return val;
        }

        private synchronized T tryTake(long sleepInterval, TimeUnit timeUnit, boolean advance) throws InterruptedException {
            if (hasDataAvailable()) {
                this.lastReadTs = System.currentTimeMillis();
                return read(true);
            }
            waitForData(sleepInterval, timeUnit);
            this.lastReadTs = System.currentTimeMillis();
            return read(advance);
        }

        private boolean hasDataAvailable() {
            if (!pageQueue.isEmpty()) {
                return true;
            }
            if (Segment.this.readOnly()) {
                return readPosition < Segment.this.logicalSize();
            }
            return readPosition < Segment.this.position();
        }

        private synchronized T tryPool(long time, TimeUnit timeUnit) throws InterruptedException {
            if (hasDataAvailable()) {
                this.lastReadTs = System.currentTimeMillis();
                return read(true);
            }
            if (time > 0) {
                waitFor(time, timeUnit);
            }
            this.lastReadTs = System.currentTimeMillis();
            return read(true);
        }

        private void waitFor(long time, TimeUnit timeUnit) throws InterruptedException {
            long elapsed = 0;
            long start = System.currentTimeMillis();
            long maxWaitTime = timeUnit.toMillis(time);
            long interval = Math.min(maxWaitTime, POLL_MS);
            while (!closed.get() && !hasDataAvailable() && elapsed < maxWaitTime) {
                TimeUnit.MILLISECONDS.sleep(interval);
                elapsed = System.currentTimeMillis() - start;
            }
        }

        private void waitForData(long time, TimeUnit timeUnit) throws InterruptedException {
            while (!closed.get() && !readOnly() && !hasDataAvailable()) {
                timeUnit.sleep(time);
                this.lastReadTs = System.currentTimeMillis();
            }
        }

        @Override
        public T peek() throws InterruptedException {
            return tryTake(POLL_MS, TimeUnit.MILLISECONDS, false);
        }

        @Override
        public T poll() throws InterruptedException {
            return tryPool(NO_SLEEP, TimeUnit.MILLISECONDS);
        }

        @Override
        public T poll(long time, TimeUnit timeUnit) throws InterruptedException {
            return tryPool(time, timeUnit);
        }

        @Override
        public T take() throws InterruptedException {
            return tryTake(POLL_MS, TimeUnit.MILLISECONDS, true);
        }

        @Override
        public boolean headOfLog() {
            return readPosition >= Segment.this.logicalSize();
        }

        @Override
        public boolean endOfLog() {
            return readOnly() && readPosition >= Segment.this.logicalSize();

        }

        @Override
        public long position() {
            return readPosition;
        }

        @Override
        public void close() {
            Segment.this.removeFromReaders(this);
        }

        @Override
        public String toString() {
            return "SegmentPoller{readPosition=" + readPosition +
                    ", lastReadTs=" + lastReadTs +
                    '}';
        }
    }

}
