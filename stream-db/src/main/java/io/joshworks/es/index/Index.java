package io.joshworks.es.index;

import io.joshworks.es.SegmentDirectory;
import io.joshworks.es.index.btree.BTreeIndexSegment;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class Index extends SegmentDirectory<BTreeIndexSegment> {

    //    private final RedBlackBST memTable;
    private final Map<Long, List<IndexEntry>> table = new ConcurrentHashMap<>();
    private final AtomicInteger entries = new AtomicInteger();
    private final int maxEntries;
    private final int blockSize;

    static final String EXT = "idx";

    public static final int NONE = -1;

    public Index(File root, int maxEntries, int blockSize, int versionCacheSize) {
        super(root, EXT);
        if (blockSize % 2 != 0) {
            throw new IllegalArgumentException("Block size must be power of two, got " + blockSize);
        }
        if (blockSize > Short.MAX_VALUE) { //block uses short for BLOCK_SIZE
            throw new IllegalArgumentException("Block size must cannot be greater than " + Short.MAX_VALUE);
        }
//        this.memTable = new RedBlackBST(maxEntries);
        this.maxEntries = maxEntries;
        this.blockSize = blockSize;
        super.loadSegments(f -> new BTreeIndexSegment(f, maxEntries, blockSize));
    }

    public void append(IndexEntry entry) {
        if (entries.get() >= maxEntries) {
            flush();
        }
        table.compute(entry.stream(), (k, v) -> {
            if (v == null) v = new ArrayList<>();
            v.add(entry);
            return v;
        });
        entries.incrementAndGet();
    }

    public int version(long stream) {
        List<IndexEntry> entries = table.get(stream);
        if (entries != null) {
            return entries.get(entries.size() - 1).version();
        }
        IndexEntry ie = readFromDisk(IndexKey.maxOf(stream), IndexFunction.FLOOR);
        if (ie == null || ie.stream() != stream) {
            return -1;
        }
        return ie.version();
    }

    public List<IndexEntry> get(IndexKey start, int count) {
        int version = start.version();

        List<IndexEntry> fromMemTable = new ArrayList<>();
        IndexEntry found;
        while ((found = getFromMemTable(start.stream(), version++)) != null && fromMemTable.size() < count) {
            fromMemTable.add(found);
        }
        if (!fromMemTable.isEmpty()) {
            return fromMemTable; //startVersion is in memtable, definitely nothing in disk
        }
        return readBatchFromDisk(start.stream(), version, count);
    }


    private List<IndexEntry> readBatchFromDisk(long stream, int version, int count) {
        if (count <= 0) {
            return Collections.emptyList();
        }
        for (int i = segments.size() - 1; i >= 0; i--) {
            BTreeIndexSegment index = segments.get(i);
            List<IndexEntry> entries = index.findBatch(stream, version, count);
            if (!entries.isEmpty()) {
                return entries;
            }
        }
        return Collections.emptyList();
    }

    public IndexEntry get(IndexKey key) {
        IndexEntry fromMemTable = getFromMemTable(key.stream(), key.version());
        if (fromMemTable != null) {
            return fromMemTable;
        }

        return readFromDisk(key, IndexFunction.EQUALS);
    }

    private IndexEntry readFromDisk(IndexKey key, IndexFunction fn) {
        for (int i = segments.size() - 1; i >= 0; i--) {
            BTreeIndexSegment index = segments.get(i);
            IndexEntry ie = index.find(key.stream(), key.version(), fn);
            if (ie != null) {
                return ie;
            }
        }
        return null;
    }

    private IndexEntry getFromMemTable(long stream, int version) {
        List<IndexEntry> entries = table.get(stream);
        if (entries == null) {
            return null;
        }
        int firstVersion = entries.get(0).version();
        int lastVersion = entries.get(entries.size() - 1).version();
        if (version < firstVersion || version > lastVersion) {
            return null;
        }
        int pos = version - firstVersion;
        return entries.get(pos);
    }

    public long entries() {
        return segments.stream().mapToLong(BTreeIndexSegment::entries).sum() + entries.get();
    }

    public void flush() {
        int entries = this.entries.get();
        System.out.println("Flushing memtable: " + entries);
        long start = System.currentTimeMillis();
        if (entries == 0) {
            return;
        }
        BTreeIndexSegment index = new BTreeIndexSegment(newSegmentFile(0), maxEntries, blockSize);
        table.entrySet().stream()
                .sorted()
                .forEach(entry -> {
                    for (IndexEntry ie : entry.getValue()) {
                        index.append(ie.stream(), ie.version(), ie.logAddress());
                    }
                });

        index.complete();

        segments.add(index);
        makeHead(index);
        this.entries.set(0);
        table.clear();

        System.out.println("Memtable flush complete in " + (System.currentTimeMillis() - start) + "ms");
    }

}
