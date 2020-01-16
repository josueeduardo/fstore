package io.joshworks.fstore.core.io.buffers;

import java.nio.Buffer;
import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;

import static io.joshworks.fstore.core.io.MemStorage.MAX_BUFFER_SIZE;

public class Buffers {

    public static ByteBuffer allocate(int size, boolean direct) {
        if (size >= MAX_BUFFER_SIZE) {
            throw new IllegalArgumentException("Buffer too large: Max allowed size is: " + MAX_BUFFER_SIZE);
        }
        return direct ? ByteBuffer.allocateDirect(size) : ByteBuffer.allocate(size);
    }

    public static int absolutePut(ByteBuffer src, int srcPos, ByteBuffer dst) {
        if (srcPos > src.position()) {
            return 0;
        }
        //do not use src.remaining() here as we don't expect flipped buffers
        int srcRemaining = src.position() - srcPos;
        int readableBytes = Math.min(srcRemaining, dst.remaining());
        copy(src, srcPos, readableBytes, dst);
        return readableBytes;
    }

    public static int copy(ByteBuffer src, int srcStart, int count, ByteBuffer dst) {

        int i = 0;
        while ((count - i) >= Long.BYTES) {
            dst.putLong(src.getLong(srcStart + i));
            i += Long.BYTES;
        }
        while ((count - i) >= Integer.BYTES) {
            dst.putInt(src.getInt(srcStart + i));
            i += Integer.BYTES;
        }
        while ((count - i) >= Short.BYTES) {
            dst.putShort(src.getShort(srcStart + i));
            i += Short.BYTES;
        }
        while ((count - i) >= Byte.BYTES) {
            dst.put(src.get(srcStart + i));
            i += Byte.BYTES;
        }

        return i;
    }

    public static void copy(ByteBuffer src, ByteBuffer dst) {
        if (src.remaining() > dst.remaining()) {
            throw new BufferOverflowException();
        }
        copy(src, src.position(), src.remaining(), dst);
    }

    public static void offsetPosition(ByteBuffer buffer, int offset) {
        buffer.position(buffer.position() + (offset));
    }

    public static int positionArrayOffset(ByteBuffer buffer) {
        if (buffer.hasArray()) {
            return buffer.position() + buffer.arrayOffset();
        }
        return buffer.position();
    }

    public static byte[] copyArray(ByteBuffer bb) {
        byte[] data = new byte[bb.remaining()];
        bb.get(data);
        return data;
    }

    /**
     * Determine whether any of the buffers has remaining data.
     *
     * @param buffers the buffers
     * @param offs    the offset into the buffers array
     * @param len     the number of buffers to check
     * @return {@code true} if any of the selected buffers has remaining data
     */
    public static boolean hasRemaining(final Buffer[] buffers, final int offs, final int len) {
        for (int i = 0; i < len; i++) {
            if (buffers[i + offs].hasRemaining()) {
                return true;
            }
        }
        return false;
    }
}
