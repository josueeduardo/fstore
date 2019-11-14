package io.joshworks.fstore.log.segment.block;

import io.joshworks.fstore.core.Serializer;
import io.joshworks.fstore.core.io.buffers.BufferPool;

import java.util.Collection;
import java.util.function.Consumer;

public class Blocks {

    public static <T> void writeAll(Collection<T> items, Block block, BufferPool bufferPool, Serializer<T> serializer, Consumer<Block> onBlockFull) {
        for (T item : items) {
            if (!block.add(item, serializer, bufferPool)) {
                onBlockFull.accept(block);
                block.clear();
                block.add(item, serializer, bufferPool);
            }
        }
        if (!block.isEmpty()) {
            onBlockFull.accept(block);
            block.clear();
        }
    }

}
