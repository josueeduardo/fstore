package io.joshworks.fstore.core.io;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public class BufferPool2 implements BufferPool{

    private static int LARGE_SIZE = 1024 * 1024;
    private ByteBuffer largeBuffer = malloc(LARGE_SIZE);
    private final List<ByteBuffer>[] potBuffers;

    public BufferPool2() {
        potBuffers = (List<ByteBuffer>[]) new List[32];
        for (int i = 0; i < potBuffers.length; i++) {
            potBuffers[i] = new ArrayList<>();
        }
    }

    @Override
    public ByteBuffer allocate(int bytes) {
        int alloc = allocSize(bytes);
        int index = Integer.numberOfTrailingZeros(alloc);
        List<ByteBuffer> list = potBuffers[index];

        ByteBuffer bb = list.isEmpty() ? create(alloc) : list.remove(list.size() - 1);
        bb.position(0).limit(bytes);

        // fill with zeroes to ensure deterministic behavior upon handling 'uninitialized' data
        for (int i = 0, n = bb.remaining(); i < n; i++) {
            bb.put(i, (byte) 0);
        }

        return bb;
    }

    @Override
    public void free(ByteBuffer buffer) {
        int alloc = allocSize(buffer.capacity());
        if (buffer.capacity() != alloc) {
            throw new IllegalArgumentException("buffer capacity not a power of two");
        }
        int index = Integer.numberOfTrailingZeros(alloc);
        potBuffers[index].add(buffer);
    }

    private ByteBuffer create(int bytes) {
        if (bytes > LARGE_SIZE)
            return malloc(bytes);

        if (bytes > largeBuffer.remaining()) {
            largeBuffer = malloc(LARGE_SIZE);
        }

        largeBuffer.limit(largeBuffer.position() + bytes);
        ByteBuffer bb = largeBuffer.slice();
        largeBuffer.position(largeBuffer.limit());
        return bb;
    }

    private static ByteBuffer malloc(int bytes) {
        return ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder());
    }

    private static int allocSize(int bytes) {
        if (bytes <= 0) {
            throw new IllegalArgumentException("attempted to allocate zero bytes");
        }
        return (bytes > 1) ? Integer.highestOneBit(bytes - 1) << 1 : 1;
    }
}
