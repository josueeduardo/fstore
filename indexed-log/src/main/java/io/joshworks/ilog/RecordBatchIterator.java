package io.joshworks.ilog;

import io.joshworks.fstore.core.RuntimeIOException;
import io.joshworks.fstore.core.io.buffers.BufferPool;
import io.joshworks.fstore.core.util.Iterators;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.WritableByteChannel;
import java.util.NoSuchElementException;

import static io.joshworks.ilog.Record.HEADER_BYTES;

public class RecordBatchIterator implements Iterators.CloseableIterator<ByteBuffer> {

    private final ByteBuffer readBuffer;
    private final IndexedSegment segment;
    private final BufferPool pool;
    private long readPos;
    private int bufferPos;
    private int bufferLimit;

    public RecordBatchIterator(IndexedSegment segment, long startPos, BufferPool pool) {
        this.pool = pool;
        this.segment = segment;
        this.readPos = startPos;
        this.readBuffer = pool.allocate();
        this.bufferLimit = readBuffer.limit();
        if (readBuffer.capacity() < HEADER_BYTES) {
            pool.free(readBuffer);
            throw new IllegalArgumentException("Read buffer must be at least " + HEADER_BYTES);
        }
    }

    @Override
    public boolean hasNext() {
        readBuffer.limit(bufferLimit).position(bufferPos);
        if (hasNext(readBuffer)) {
            return true;
        }
        readBatch();
        return hasNext(readBuffer);
    }

    @Override
    public ByteBuffer next() {
        readBuffer.limit(bufferLimit).position(bufferPos);
        if (!hasNext(readBuffer)) {
            throw new NoSuchElementException();
        }
        bufferPos += Record2.validate(readBuffer);
        return readBuffer;
    }

    public ByteBuffer peek() {
        if (!hasNext()) {
            throw new NoSuchElementException();
        }
        return readBuffer;
    }

    private boolean hasNext(ByteBuffer record) {
        int remaining = record.remaining();
        if (remaining < Record2.HEADER_BYTES) {
            return false;
        }
        int rsize = Record2.sizeOf(record);
        return rsize <= remaining && rsize > Record2.HEADER_BYTES;
    }

    public long transferTo(WritableByteChannel channel) throws IOException {
        long written = 0;
        while (hasNext() && !endOfLog()) {
            ByteBuffer buffer = next();
            int w = Record2.writeTo(buffer, channel);
            if (w > 0) written += w;
        }
        return written;
    }

    private void readBatch() {
        try {
            if (readPos + bufferPos >= segment.writePosition()) {
                return;
            }
            readPos += bufferPos;
            long rpos = readPos;
            if (readBuffer.position() > 0) {
                readBuffer.compact();
                rpos += readBuffer.position();
            }

            int read = segment.read(rpos, readBuffer);
            if (read <= 0) { //EOF or no more data
                throw new IllegalStateException("Expected data to be read");
            }
            readBuffer.flip();
            bufferPos = 0;
            bufferLimit = readBuffer.limit();
        } catch (IOException e) {
            throw new RuntimeIOException("Failed to read batch", e);
        }
    }

    //internal
    long position() {
        return readPos;
    }

    public boolean endOfLog() {
        return segment.readOnly() && !hasReadableBytes() && !hasNext();
    }

    private boolean hasReadableBytes() {
        return segment.writePosition() - readPos > 0;
    }

    @Override
    public void close() {
        pool.free(readBuffer);
    }
}
