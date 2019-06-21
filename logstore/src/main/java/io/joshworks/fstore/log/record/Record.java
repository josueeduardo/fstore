package io.joshworks.fstore.log.record;

import java.io.Closeable;
import java.nio.ByteBuffer;

class Record implements Closeable {

    private final ByteBuffer[] buffers = new ByteBuffer[]{ByteBuffer.allocateDirect(RecordHeader.MAIN_HEADER), null, ByteBuffer.allocateDirect(RecordHeader.SECONDARY_HEADER)};

    ByteBuffer[] create(ByteBuffer data) {
        int entrySize = data.remaining();

        buffers[0].putInt(entrySize);
        buffers[0].putInt(ByteBufferChecksum.crc32(data));
        buffers[0].flip();

        buffers[1] = data;

        buffers[2].putInt(entrySize);
        buffers[2].flip();
        return buffers;
    }

    long size() {
        return buffers[0].remaining() + buffers[1].remaining() + buffers[2].remaining();
    }

    @Override
    public void close()  {
        buffers[0].clear();
        buffers[1] = null;
        buffers[2].clear();
    }
}
