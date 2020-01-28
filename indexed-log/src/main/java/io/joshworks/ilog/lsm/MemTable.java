package io.joshworks.ilog.lsm;

import io.joshworks.fstore.core.io.buffers.BufferPool;
import io.joshworks.fstore.core.io.buffers.Buffers;
import io.joshworks.ilog.Log;
import io.joshworks.ilog.Record2;
import io.joshworks.ilog.index.KeyComparator;

import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicInteger;

import static java.util.Objects.requireNonNull;

class MemTable {

    final ConcurrentSkipListMap<ByteBuffer, ByteBuffer> table;
    private final KeyComparator comparator;
    private final BufferPool keyPool;
    private final AtomicInteger size = new AtomicInteger();

    MemTable(KeyComparator comparator, BufferPool keyPool) {
        this.table = new ConcurrentSkipListMap<>(comparator);
        this.comparator = comparator;
        this.keyPool = keyPool;
    }

    int add(ByteBuffer record) {
        requireNonNull(record, "Record must be provided");
        var keyBuffer = keyPool.allocate();
        try {
            Record2.writeKey(record, keyBuffer);
            keyBuffer.flip();
            validateKeySize(keyBuffer);
            ByteBuffer existing = table.put(keyBuffer, record);
            if (existing != null) {
                return size.get();
            }
            return size.incrementAndGet();

        } catch (Exception e) {
            keyPool.free(keyBuffer);
            throw new RuntimeException("Failed to insert record", e);
        }
    }

    public int get(ByteBuffer key, ByteBuffer dst) {
        validateKeySize(key);
        var floorRecord = floor(key);
        if (floorRecord == null) {
            return 0;
        }
        int compare = Record2.compareToKey(floorRecord, key, comparator);
        if (compare != 0) {
            return 0;
        }
        return Buffers.copy(floorRecord, dst);
    }

    public ByteBuffer get(ByteBuffer key) {
        validateKeySize(key);
        var floorRecord = floor(key);
        if (floorRecord == null) {
            return null;
        }
        int compare = Record2.compareToKey(floorRecord, key, comparator);
        return compare == 0 ? floorRecord : null;
    }

    public ByteBuffer floor(ByteBuffer key) {
        var entry = table.floorEntry(key);
        return getValue(entry);
    }

    public ByteBuffer ceiling(ByteBuffer key) {
        var entry = table.ceilingEntry(key);
        return getValue(entry);
    }

    public ByteBuffer higher(ByteBuffer key) {
        var entry = table.higherEntry(key);
        return getValue(entry);
    }

    public ByteBuffer lower(ByteBuffer key) {
        var entry = table.lowerEntry(key);
        return getValue(entry);
    }

    public int size() {
        return size.get();
    }

    private void validateKeySize(ByteBuffer key) {
        if (key.remaining() != comparator.keySize()) {
            throw new IllegalArgumentException("Invalid key size: " + key.remaining());
        }
    }

    private static ByteBuffer getValue(Map.Entry<ByteBuffer, ByteBuffer> entry) {
        return entry == null ? null : entry.getValue();
    }

    long writeTo(Log<SSTable> sstables, long maxAge) {
        if (table.isEmpty()) {
            return 0;
        }

        long inserted = 0;
        for (ByteBuffer entry : table.values()) {
            if (LsmRecord.expired(entry, maxAge) && !LsmRecord.deletion(entry)) {
                continue;
            }
            sstables.append(entry);
        }
        sstables.roll();

        size.set(0); // TODO remove
        table.clear(); // TODO remove
        return inserted;
    }

}
