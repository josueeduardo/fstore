package io.joshworks.eventry.index.disk;

import io.joshworks.eventry.index.IndexEntry;
import io.joshworks.fstore.codec.snappy.SnappyCodec;
import io.joshworks.fstore.core.Codec;
import io.joshworks.fstore.core.Serializer;
import io.joshworks.fstore.log.segment.block.Block;
import org.junit.Test;

import java.nio.ByteBuffer;

import static org.hamcrest.core.Is.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

public class IndexBlockTest {

    private final Serializer<IndexEntry> serializer = new IndexEntrySerializer();

    @Test
    public void packing_empty_block_returns_empty_buffer() {
        var block = new IndexBlock(4096);
        var packed = block.pack(Codec.noCompression());
        assertEquals(0, packed.limit());
    }

    @Test
    public void packed_buffer_is_ready_to_read_from() {
        var block = new IndexBlock(4096);
        addTo(block, IndexEntry.of(1, 1, 1));
        var packed = block.pack(Codec.noCompression());
        assertTrue(packed.remaining() > 0);
    }

    @Test
    public void unpacking_has_the_same_entries() {
        var block = new IndexBlock(4096);

        long stream = 123;
        int version = 0;
        long position = 456;

        boolean added;
        do {
            added = addTo(block, IndexEntry.of(stream, version, position));
        } while (added);


        Codec codec = new SnappyCodec();
        ByteBuffer packed = block.pack(codec);

        var unpacked = new IndexBlock(codec, packed);

        assertThat(block.entries(), is(unpacked.entries()));
    }

    @Test
    public void entryCount_returns_right_count() {

        var block = new IndexBlock(4096);

        int items = 100;
        long stream = 123;
        long position = 456;
        for (int i = 0; i < items; i++) {
            addTo(block, IndexEntry.of(stream, i, position));
        }

        assertEquals(items, block.entryCount());
    }

    private boolean addTo(Block block, IndexEntry ie) {
        return block.add(serializer.toBytes(ie));
    }


}