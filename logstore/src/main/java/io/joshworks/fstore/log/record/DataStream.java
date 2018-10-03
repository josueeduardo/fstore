package io.joshworks.fstore.log.record;

import io.joshworks.fstore.core.io.BufferPool;
import io.joshworks.fstore.core.io.Storage;
import io.joshworks.fstore.core.util.Memory;
import io.joshworks.fstore.log.Checksum;
import io.joshworks.fstore.log.ChecksumException;
import io.joshworks.fstore.log.Direction;
import io.joshworks.fstore.log.segment.Log;

import java.nio.ByteBuffer;
import java.util.Random;

//THREAD SAFE
public class DataStream implements IDataStream {


    private static final double DEFAULT_CHECKUM_PROB = 1;

    //hard limit is required to memory issues in case of broken record
    public static final int MAX_ENTRY_SIZE = 1024 * 1024 * 5;

    private final double checksumProb;
    private final Random rand = new Random();
    private final RecordReader forwardReader = new ForwardRecordReader();
    private final RecordReader backwardReader = new BackwardRecordReader();

    public DataStream() {
        this(DEFAULT_CHECKUM_PROB);
    }

    public DataStream(double checksumProb) {
        this.checksumProb = (int) (checksumProb * 100);
        if (checksumProb < 0 || checksumProb > 1) {
            throw new IllegalArgumentException("Checksum verification frequency must be between 0.0 and 1.0");
        }
    }

    @Override
    public long write(Storage storage, BufferPool bufferPool, ByteBuffer bytes) {
        int recordSize = RecordHeader.HEADER_OVERHEAD + bytes.remaining();
        long entryPos = storage.position();

        if (recordSize > MAX_ENTRY_SIZE) {
            throw new IllegalArgumentException("Record cannot exceed " + MAX_ENTRY_SIZE + " bytes");
        }

        ByteBuffer bb = bufferPool.allocate(recordSize);
        try {
            int entrySize = bytes.remaining();
            bb.putInt(entrySize);
            bb.putInt(Checksum.crc32(bytes));
            bb.put(bytes);
            bb.putInt(entrySize);

            bb.flip();
            storage.write(bb);
            return entryPos;

        } finally {
            bufferPool.free(bb);
        }
    }

    @Override
    public BufferRef read(Storage storage, BufferPool bufferPool, Direction direction, long position) {
        RecordReader reader = Direction.FORWARD.equals(direction) ? forwardReader : backwardReader;
        return reader.read(storage, bufferPool, position);
    }

    private void checksum(int expected, ByteBuffer data, long position) {
        if (checksumProb == 0) {
            return;
        }
        if (rand.nextInt(100) < checksumProb && Checksum.crc32(data) != expected) {
            throw new ChecksumException(position);
        }
    }

    private final class ForwardRecordReader implements RecordReader {

        @Override
        public BufferRef read(Storage storage, BufferPool bufferPool, long position) {
            ByteBuffer buffer = bufferPool.allocate(Memory.PAGE_SIZE);
            storage.read(position, buffer);
            buffer.flip();

            if (buffer.remaining() == 0) {
                return BufferRef.ofEmpty();
            }

            int length = buffer.getInt();
            checkRecordLength(length, position);
            if (length == 0) {
                return BufferRef.ofEmpty();
            }

            int recordSize = length + RecordHeader.HEADER_OVERHEAD;
            if (recordSize > buffer.limit()) {
                bufferPool.free(buffer);
                buffer = bufferPool.allocate(recordSize);
                storage.read(position, buffer);
                buffer.flip();
                buffer.getInt(); //skip length
            }

            buffer.position(buffer.position() - Integer.BYTES);
            int[] markers = new int[100];
            int[] lengths = new int[100];
            int i = 0;
            while(buffer.hasRemaining()) {
                int pos = buffer.position();
                int len = buffer.getInt();
                if(len == 0) {
                    return BufferRef.withMarker(buffer, bufferPool, markers, lengths);
                }
                if(buffer.remaining() > len) {
                    markers[i] = pos + RecordHeader.MAIN_HEADER;
                    lengths[i] = len;
                }

                int checksum = buffer.getInt();
                buffer.limit(buffer.position() + length);
                checksum(checksum, buffer, pos);
                buffer.limit(buffer.capacity());
                buffer.position(buffer.position() + len + RecordHeader.SECONDARY_HEADER);
                i++;
            }
            return BufferRef.withMarker(buffer, bufferPool, markers, lengths);


        }
    }

    private final class BackwardRecordReader implements RecordReader {

        @Override
        public BufferRef read(Storage storage, BufferPool bufferPool, long position) {
            ByteBuffer buffer = bufferPool.allocate(Memory.PAGE_SIZE);
            int limit = buffer.limit();
            if (position - limit < Log.START) {
                int available = (int) (position - Log.START);
                if (available == 0) {
                    return BufferRef.ofEmpty();
                }
                buffer.limit(available);
                limit = available;
            }

            storage.read(position - limit, buffer);
            buffer.flip();
            if (buffer.remaining() == 0) {
                return BufferRef.ofEmpty();
            }

            int recordDataEnd = buffer.limit() - RecordHeader.SECONDARY_HEADER;
            int length = buffer.getInt(recordDataEnd);
            checkRecordLength(length, position);
            if (length == 0) {
                return BufferRef.ofEmpty();
            }

            int recordSize = length + RecordHeader.HEADER_OVERHEAD;

            if (recordSize > buffer.limit()) {
                bufferPool.free(buffer);
                buffer = bufferPool.allocate(recordSize);

                buffer.limit(recordSize - RecordHeader.SECONDARY_HEADER); //limit to the entry size, excluding the secondary header
                long readStart = position - recordSize;
                storage.read(readStart, buffer);
                buffer.flip();

                int foundLength = buffer.getInt();
                checkRecordLength(foundLength, position);
                int checksum = buffer.getInt();
                checksum(checksum, buffer, position);
                return BufferRef.of(buffer, bufferPool);

            }

            buffer.limit(recordDataEnd);
            buffer.position(recordDataEnd - length - RecordHeader.CHECKSUM_SIZE);
            int checksum = buffer.getInt();
            checksum(checksum, buffer, position);
            return BufferRef.of(buffer, bufferPool);
        }
    }

    private void checkRecordLength(int length, long position) {
        if (length < 0 || length > MAX_ENTRY_SIZE) {
            throw new IllegalStateException("Invalid length " + length + " at position " + position);
        }
    }

}
