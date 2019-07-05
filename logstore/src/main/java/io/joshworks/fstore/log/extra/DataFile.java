package io.joshworks.fstore.log.extra;

import io.joshworks.fstore.core.Serializer;
import io.joshworks.fstore.core.io.IOUtils;
import io.joshworks.fstore.core.io.Storage;
import io.joshworks.fstore.core.io.StorageMode;
import io.joshworks.fstore.core.io.buffers.LocalGrowingBufferPool;
import io.joshworks.fstore.core.util.Memory;
import io.joshworks.fstore.core.util.Size;
import io.joshworks.fstore.log.Direction;
import io.joshworks.fstore.log.record.DataStream;
import io.joshworks.fstore.log.record.RecordEntry;

import java.io.Closeable;
import java.io.File;
import java.io.Flushable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.List;
import java.util.Queue;

public class DataFile<T> implements Flushable, Closeable {

    //safe integer value
    private final DataStream stream;
    private final Storage storage;
    private final Serializer<T> serializer;

    private DataFile(File handler, Serializer<T> serializer, boolean mmap, long initialSize, double checksumProb, int maxEntrySize) {
        Storage storage = null;
        try {
            this.storage = storage = Storage.createOrOpen(handler, mmap ? StorageMode.MMAP : StorageMode.RAF, initialSize);
            this.stream = new DataStream(new LocalGrowingBufferPool(), storage, maxEntrySize, checksumProb, Memory.PAGE_SIZE * 4);
            this.serializer = serializer;
        } catch (Exception e) {
            IOUtils.closeQuietly(storage);
            throw new RuntimeException(e);
        }
    }

    public static <T> Builder<T> of(Serializer<T> serializer) {
        return new Builder<>(serializer);
    }

    public synchronized long add(T record) {
        ByteBuffer data = serializer.toBytes(record);
        long pos = stream.write(data);
        if (Storage.EOF == pos) {
            throw new RuntimeException("Not space left in the data file");
        }
        return pos;
    }

    public long length() {
        return storage.length();
    }

    public T get(long position) {
        RecordEntry<T> record = stream.read(Direction.FORWARD, position, serializer);
        return record == null ? null : record.entry();
    }

    public Iterator<T> iterator(Direction direction) {
        long pos = Direction.FORWARD.equals(direction) ? 0 : stream.position();
        return iterator(direction, pos);
    }

    public Iterator<T> iterator(Direction direction, long position) {
        position = position > stream.position() ? stream.position() : position;
        return new DataFileIterator<>(stream, serializer, direction, position);
    }

    @Override
    public void flush() {
        try {
            storage.flush();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void close() {
        IOUtils.closeQuietly(storage);
    }

    public synchronized void delete() {
        storage.delete();
    }

    private static class DataFileIterator<T> implements Iterator<T> {

        private final DataStream stream;
        private final Serializer<T> serializer;
        private final Queue<RecordEntry<T>> entries = new ArrayDeque<>();
        private final Direction direction;
        private long position;

        private DataFileIterator(DataStream stream, Serializer<T> serializer, Direction direction, long position) {
            this.stream = stream;
            this.serializer = serializer;
            this.direction = direction;
            this.position = position;
        }

        private void fetchEntries() {
            if (position >= stream.position()) {
                return;
            }
            List<RecordEntry<T>> read = stream.bulkRead(direction, position, serializer);
            entries.addAll(read);
        }

        @Override
        public boolean hasNext() {
            if (entries.isEmpty()) {
                fetchEntries();
            }
            return entries.isEmpty();
        }

        @Override
        public T next() {
            if (!hasNext()) {
                return null;
            }
            RecordEntry<T> record = entries.poll();
            position += record.recordSize();
            return record.entry();
        }
    }

    public static final class Builder<T> {

        private final Serializer<T> serializer;
        private boolean mmap;
        private int maxEntrySize = Size.MB.intOf(5);
        private long size;
        private double checksumProb = 0.1;

        private Builder(Serializer<T> serializer) {
            this.serializer = serializer;
        }

        public Builder<T> mmap() {
            this.mmap = true;
            return this;
        }

        public Builder<T> maxEntrySize(int maxSize) {
            this.maxEntrySize = maxSize;
            return this;
        }

        public Builder<T> checksumProbability(double checksumProb) {
            this.checksumProb = checksumProb;
            return this;
        }

        public Builder<T> withInitialSize(long size) {
            this.size = size;
            return this;
        }

        public DataFile<T> open(File file) {
            return new DataFile<>(file, serializer, mmap, size, checksumProb, maxEntrySize);
        }
    }
}
