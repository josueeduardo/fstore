package io.joshworks.eventry;

import io.joshworks.eventry.index.IndexKey;
import io.joshworks.eventry.index.IndexKeySerializer;
import io.joshworks.eventry.log.EventSerializer;
import io.joshworks.fstore.codec.snappy.SnappyCodec;
import io.joshworks.fstore.core.cache.Cache;
import io.joshworks.fstore.core.io.StorageMode;
import io.joshworks.fstore.core.metrics.Metrics;
import io.joshworks.fstore.core.util.FileUtils;
import io.joshworks.fstore.core.util.Size;
import io.joshworks.fstore.core.util.Threads;
import io.joshworks.fstore.es.shared.EventRecord;
import io.joshworks.fstore.es.shared.streams.StreamHasher;
import io.joshworks.fstore.log.Direction;
import io.joshworks.fstore.log.iterators.Iterators;
import io.joshworks.fstore.lsmtree.LsmTree;
import io.joshworks.fstore.lsmtree.sstable.Entry;
import io.joshworks.fstore.lsmtree.sstable.Expression;
import io.joshworks.fstore.serializer.json.JsonSerializer;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static io.joshworks.fstore.es.shared.EventId.NO_VERSION;

public class EStore {

    private static final String STREAM_PREFIX = "stream-";
    private static final int EVENTS_PER_STREAM = 400;
    private static final int STREAMS = 500000;
    private static final int THREADS = 1;
    public static final int FLUSH_THRESHOLD = 1000000;
    private static LsmTree<IndexKey, EventRecord> store;

    private static final Metrics metrics = new Metrics();

    public static void main(String[] args) {
        File dir = new File("S:\\es-server-1");

        FileUtils.tryDelete(dir);

        store = LsmTree
                .builder(dir, new IndexKeySerializer(), new EventSerializer())
                .codec(new SnappyCodec())
                .flushThreshold(FLUSH_THRESHOLD)
                .bloomFilterFalsePositiveProbability(0.1)
                .blockCache(Cache.noCache())
                .sstableStorageMode(StorageMode.MMAP)
                .transactionLogStorageMode(StorageMode.MMAP)
                .maxEntrySize(Size.MB.ofInt(6))
                .blockSize(Size.KB.ofInt(8))
                .parallelCompaction(false)
                .useDirectBufferPool(true)
                .flushQueueSize(3)
                .open();

        Thread monitor = new Thread(EStore::monitor);
        monitor.start();

        write();

//        CloseableIterator<Entry<IndexKey, EventRecord>> iterator = store.iterator(Direction.FORWARD);
//        int i = 0;
//        long s = System.currentTimeMillis();
//        while(iterator.hasNext()) {
//            Entry<IndexKey, EventRecord> next = iterator.next();
//            i++;
//        }
//        System.out.println("RREEEEEAD " + (System.currentTimeMillis() - s) + " ---- " + i);

        for (int i = 0; i < STREAMS; i++) {
            List<EventRecord> events = events(STREAM_PREFIX + i);
        }
    }

    private static EventRecord add(String stream, Object data) {
        long hash = StreamHasher.hash(stream);
        int version = getVersion(hash) + 1;
        byte[] dataBytes = JsonSerializer.toBytes(data);
        byte[] metadataBytes = new byte[0];
        EventRecord record = new EventRecord(stream, "type-1", version, System.currentTimeMillis(), dataBytes, metadataBytes);

        store.put(IndexKey.event(hash, version), record);
        return record;
    }

    private static int getVersion(long stream) {
        IndexKey maxStreamVersion = IndexKey.event(stream, Integer.MAX_VALUE);
        Entry<IndexKey, EventRecord> found = store.find(maxStreamVersion, Expression.FLOOR, entry -> entry.key.stream == stream);
        return found != null ? found.key.version : NO_VERSION;
    }

    private static List<EventRecord> events(String stream) {
        List<EventRecord> records = Iterators.stream(store.iterator(Direction.FORWARD, IndexKey.allOf(StreamHasher.hash(stream))))
                .map(kv -> kv.value)
                .collect(Collectors.toList());

        updateReadMetrics(records.size());

        if (records.size() != EVENTS_PER_STREAM) {
            throw new RuntimeException("Not expected: " + records.size());
        }

        return records;
    }

    private static void updateReadMetrics(int recordSize) {
        metrics.update("readStream");
        metrics.update("readStreamPerSec");
        metrics.update("readEventsPerSec", recordSize);
        metrics.update("totalEvents", recordSize);
    }

    private static List<EventRecord> events1(String stream) {
        List<EventRecord> records = new ArrayList<>();
        long hash = StreamHasher.hash(stream);
        int version = 0;
        EventRecord record;
        do {
            record = store.get(IndexKey.event(hash, version));
            if (record != null) {
                records.add(record);
            }
            version++;
        } while (record != null);

        updateReadMetrics(records.size());

        if (records.size() != EVENTS_PER_STREAM) {
            throw new RuntimeException("Not expected: " + records.size());
        }

        return records;
    }

    private static void write() {
        long s = System.currentTimeMillis();
        for (int stream = 0; stream < STREAMS; stream++) {
            for (int version = 0; version < EVENTS_PER_STREAM; version++) {
                String streamName = STREAM_PREFIX + (stream % STREAMS);
                long start = System.currentTimeMillis();
                add(streamName, new UserCreated("josh", version));
                metrics.update("writeTime", (System.currentTimeMillis() - start));
                metrics.update("writes");
                metrics.update("totalWrites");
            }
        }
        System.out.println("WRITES: " + (STREAMS * EVENTS_PER_STREAM) + " IN " + (System.currentTimeMillis() - s));
    }

    private static void monitor() {
        while (true) {
            long writes = metrics.remove("writes");
            long reads = metrics.remove("reads");
            long totalWrites = metrics.get("totalWrites");
            long totalReads = metrics.get("totalReads");
            long writeTime = metrics.get("writeTime");

            long streamReads = metrics.get("readStream");
            long totalEvents = metrics.get("totalEvents");
            long readStreamPerSec = metrics.remove("readStreamPerSec");
            long readEventsPerSec = metrics.remove("readEventsPerSec");

            long avgWriteTime = writeTime == 0 ? 0 : writeTime / totalWrites;
            System.out.println(
                    "WRITE: " + writes + "/s - " +
                            "TOTAL WRITE: " + totalWrites +
                            " AVG_WRITE_TIME: " + avgWriteTime +
                            " READ: " + reads + "/s " +
                            " TOTAL READ: " + totalReads +
                            " STREAM_READ: " + streamReads +
                            " TOTAL_EVENTS: " + totalEvents +
                            " STREAM_READ_PER_SEC:" + readStreamPerSec +
                            " EVENTS_READ_PER_SEC:" + readEventsPerSec);
            Threads.sleep(1000);
        }
    }

}