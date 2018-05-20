package io.joshworks.fstore.core.io;


import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.List;


public class MMapStorage extends DiskStorage {

    private static final int NO_BUFFER = -1;

    private final int bufferSize;
    private MappedByteBuffer current;
    private final List<MappedByteBuffer> buffers = new ArrayList<>();
    private int writeBufferIdx;

    public MMapStorage(File file, long length, Mode mode, int bufferSize) {
        super(file, length, mode);
        this.bufferSize = bufferSize;

        try {
            long fileLength = raf.length();
            if (bufferSize >= fileLength) {
                //TEST-CASE: if buffer size is greater than file, use bufferSize
                MappedByteBuffer singleBuffer = map(0, bufferSize);
                buffers.add(singleBuffer);
                writeBufferIdx = 0;
                current = buffers.get(writeBufferIdx);
                return;
            }

            long numFullBuffers = (fileLength / bufferSize);
            long diff = fileLength % bufferSize;
            long totalBuffers = numFullBuffers = diff > 0 ? numFullBuffers : numFullBuffers + 1;

            if (Mode.READ_WRITE.equals(mode)) {
                //load everything at one, can it be improved ?
                for (long i = 0; i < totalBuffers; i++) {
                    MappedByteBuffer buffer = map(i * bufferSize, bufferSize);
                    buffers.add(buffer);
                }
                writeBufferIdx = 0;
                current = buffers.get(writeBufferIdx);

            } else if (Mode.READ.equals(mode)) { //readOnly, do not extend file size
                //load everything at one, can it be improved ?
                for (long i = 0; i < numFullBuffers; i++) {
                    MappedByteBuffer buffer = map(i * bufferSize, bufferSize);
                    buffers.add(buffer);
                }

                current = buffers.get(0);
                if (diff > 0) {
                    MappedByteBuffer last = map((numFullBuffers + 1) * bufferSize, diff);
                    buffers.add(last);
                }
            }

        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public int write(ByteBuffer data) {
        ensureNonEmpty(data);
        checkWritable();

        if (!current.hasRemaining()) {
            nextBuffer();
        }

        if (current.remaining() < data.remaining()) {
            //grow enough to fit the new data, this should be avoided as much as possible

            int spaceLeft = current.remaining();
            ByteBuffer slice = data.slice();
            slice.limit(spaceLeft);
            current.put(slice);
            position += spaceLeft;
            data.position(spaceLeft);

            nextBuffer();

            int secondFragment = write(data);
            return spaceLeft + secondFragment;
        }

        int written = data.remaining();
        current.put(data);
        position += written;
        return written;
    }

    @Override
    public int read(long position, ByteBuffer data) {
        int idx = bufferIdx(position);
        if (idx == NO_BUFFER) {
            return 0;
        }
        MappedByteBuffer buffer = getBuffer(idx);
        int bufferAddress = posOnBuffer(position);

        ByteBuffer readOnly = buffer.asReadOnlyBuffer();
        if (position > readOnly.capacity() && idx >= buffers.size()) {
            throw new StorageException("Position " + position + "is out of limit (" + readOnly.capacity() + ")");
        }

        readOnly.position(bufferAddress);
        if (readOnly.remaining() < data.remaining()) {
            int limit = readOnly.remaining();
            readOnly.limit(bufferAddress + limit);
            data.put(readOnly);
            return limit + read(position + limit, data);
        }

        //source buffer has enough data
        int limit = data.remaining();
        readOnly.limit(bufferAddress + limit);
        data.put(readOnly);
        return limit;
    }

    @Override
    public void position(long position) {
        current.position((int) position);
        super.position = position;
    }


    private MappedByteBuffer getBuffer(int idx) {
        return buffers.get(idx);
    }

    private int posOnBuffer(long pos) {
        return (int) (pos % bufferSize);
    }

    private int bufferIdx(long pos) {
        int idx = (int) (pos / bufferSize);
        if (idx < 0 || idx >= buffers.size()) {
            return NO_BUFFER;
        }
        return idx;
    }

    private void checkWritable() {
        if (Mode.READ.equals(mode)) {
            throw new StorageException("Storage is readonly");
        }
    }

    private void nextBuffer() {
        current.flip();
        writeBufferIdx++;
        long numBuffers = buffers.size();
        if (writeBufferIdx >= numBuffers) {
            //TODO will the lock be extended for the entire file ? Or a new lock for the extended region is needed
            MappedByteBuffer next = map(numBuffers * bufferSize, bufferSize);
            buffers.add(next);
        }
        current = buffers.get(writeBufferIdx);
    }

    private void expand() {
        try {
            long fileLength = raf.length();
            long totalSize = ((long) ((buffers.size()) * bufferSize));
            long newFileSize = totalSize + bufferSize;
            if (totalSize + bufferSize > fileLength) {
                raf.setLength(newFileSize);
            }

            MappedByteBuffer newBuffer = map(position(), bufferSize);
            buffers.add(newBuffer);
            current = newBuffer;

        } catch (Exception e) {
            throw new StorageException("Failed to expand buffer", e);
        }

    }

    private MappedByteBuffer map(long from, long size) {
        try {
            FileChannel.MapMode mapMode = Mode.READ_WRITE.equals(mode) ? FileChannel.MapMode.READ_WRITE : FileChannel.MapMode.READ_ONLY;
            return raf.getChannel().map(mapMode, from, size);
        } catch (Exception e) {
            close();
            throw new StorageException(e);
        }
    }

    @Override
    public long position() {
        return position;
    }

    @Override
    public void delete() {
        unmapAll();
        super.delete();
    }

    @Override
    public void close() {
        flush();
        unmapAll();
        super.close();
    }

    private void unmapAll() {
        for (MappedByteBuffer buffer : buffers) {
            unmap(buffer);
        }
        buffers.clear();
        current = null;
    }

    private void unmap(MappedByteBuffer buffer) {
        if (buffer == null) {
            return;
        }
        try {
            Class<?> fcClass = channel.getClass();
            Method unmapMethod = fcClass.getDeclaredMethod("unmap", MappedByteBuffer.class);
            unmapMethod.setAccessible(true);
            unmapMethod.invoke(null, buffer);

        } catch (Exception e) {
            throw new StorageException(e);
        }
    }

    @Override
    public void shrink() {
        super.mode = Mode.READ;
    }

    @Override
    public void flush() {
        if (current != null && Mode.READ_WRITE.equals(mode))
            current.force();
    }
}