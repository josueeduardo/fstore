package io.joshworks.fstore.core.io;

import io.joshworks.fstore.core.util.MappedByteBuffers;

import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.locks.Lock;
import java.util.function.BiFunction;

//Not thread safe
public class MMapStorage extends MemStorage {

    private static final boolean isWindows = System.getProperty("os.name").toLowerCase().startsWith("win");
    protected final DiskStorage diskStorage;

    public MMapStorage(DiskStorage diskStorage) {
        super(diskStorage.name(), diskStorage.length(), mmap(diskStorage));
        this.diskStorage = diskStorage;
    }

    private static BiFunction<Long, Integer, ByteBuffer> mmap(DiskStorage diskStorage) {
        return (from, size) -> map(diskStorage, from, size);
    }

    MMapStorage(DiskStorage diskStorage, int bufferSize) {
        super(diskStorage.name(), diskStorage.length(), mmap(diskStorage));
        this.diskStorage = diskStorage;
    }

    protected static ByteBuffer map(DiskStorage diskStorage, long from, int size) {
        try {
            return diskStorage.channel.map(FileChannel.MapMode.READ_WRITE, from, size);
        } catch (Exception e) {
            throw new StorageException("Failed to map buffer from: " + from + ", size: " + size, e);
        }
    }

    @Override
    protected void destroy(ByteBuffer buffer) {
        MappedByteBuffers.unmap((MappedByteBuffer) buffer);
    }

    @Override
    public void flush() {
        if (isWindows) {
            //caused by https://bugs.openjdk.java.net/browse/JDK-6539707
            return;
        }
        Lock lock = readLock();
        try {
            for (ByteBuffer buffer : buffers) {
                ((MappedByteBuffer) buffer).force();
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void close() {
        super.close();
        diskStorage.close();
    }

    @Override
    public void writePosition(long position) {
        super.writePosition(position);
        diskStorage.writePosition(position);
    }

    @Override
    protected void computeLength() {
        super.computeLength();
    }

    @Override
    public void delete() {
        super.delete();
        diskStorage.delete();
    }

    @Override
    public void truncate() {
        Lock lock = writeLockInterruptibly();
        try {
            Iterator<ByteBuffer> iterator = buffers.iterator();
            while (iterator.hasNext()) {
                MappedByteBuffer buffer = (MappedByteBuffer) iterator.next();
                MappedByteBuffers.unmap(buffer);
                iterator.remove();
            }
            long pos = writePosition();
            diskStorage.writePosition(pos);
            diskStorage.truncate();
            long newLength = diskStorage.length();
            List<ByteBuffer> newBuffers = initBuffers(newLength, mmap(diskStorage));
            this.buffers.addAll(newBuffers);
            computeLength();
        } finally {
            lock.unlock();
        }
    }
}