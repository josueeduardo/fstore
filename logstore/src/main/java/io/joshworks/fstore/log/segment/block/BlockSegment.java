package io.joshworks.fstore.log.segment.block;

import io.joshworks.fstore.core.Codec;
import io.joshworks.fstore.core.Serializer;
import io.joshworks.fstore.core.io.Storage;
import io.joshworks.fstore.core.io.StorageMode;
import io.joshworks.fstore.core.io.buffers.BufferPool;
import io.joshworks.fstore.log.Direction;
import io.joshworks.fstore.log.SegmentIterator;
import io.joshworks.fstore.log.record.RecordEntry;
import io.joshworks.fstore.log.segment.Log;
import io.joshworks.fstore.log.segment.Segment;
import io.joshworks.fstore.log.segment.SegmentFactory;
import io.joshworks.fstore.log.segment.WriteMode;
import io.joshworks.fstore.log.segment.footer.FooterReader;
import io.joshworks.fstore.log.segment.footer.FooterWriter;
import io.joshworks.fstore.log.segment.header.Type;
import io.joshworks.fstore.serializer.kryo.KryoStoreSerializer;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public class BlockSegment<T> implements Log<T> {

    private static final String BLOCK_INFO_FOOTER_ITEM = "BLOCK_INFO";

    private final Serializer<T> serializer;
    private final BiConsumer<Long, Block> blockWriteListener;
    private final BiConsumer<Long, Block> onBlockLoaded;
    private final BufferPool bufferPool;
    private Consumer<FooterWriter> footerWriter;
    private final Block writeBlock;

    private BlockSegmentInfo info;

    private final Segment<Block> delegate;
    private FooterReader footerReader;
    private final KryoStoreSerializer<BlockSegmentInfo> infoSerializer = KryoStoreSerializer.of(BlockSegmentInfo.class);

    public BlockSegment(File file,
                        StorageMode storageMode,
                        long dataLength,
                        BufferPool bufferPool,
                        WriteMode writeMode,
                        Serializer<T> serializer,
                        BlockFactory blockFactory,
                        Codec codec,
                        int blockSize,
                        double checksumProb,
                        int readPageSize) {
        this(
                file,
                storageMode,
                dataLength,
                bufferPool,
                writeMode,
                serializer,
                blockFactory,
                codec,
                blockSize,
                checksumProb,
                readPageSize,
                (p, b) -> {
                },
                (p, b) -> {
                },
                fWriter -> {
                });
    }

    public BlockSegment(File file,
                        StorageMode storageMode,
                        long dataLength,
                        BufferPool bufferPool,
                        WriteMode writeMode,
                        Serializer<T> serializer,
                        BlockFactory blockFactory,
                        Codec codec,
                        int blockSize,
                        double checksumProb,
                        int readPageSize,
                        BiConsumer<Long, Block> blockWriteListener,
                        BiConsumer<Long, Block> onBlockLoaded,
                        Consumer<FooterWriter> footerWriter) {

        this.serializer = serializer;
        this.bufferPool = bufferPool;
        this.info = new BlockSegmentInfo(blockSize);
        this.blockWriteListener = blockWriteListener;
        this.onBlockLoaded = onBlockLoaded;
        this.footerWriter = footerWriter;
        this.writeBlock = blockFactory.create(blockSize);
        this.delegate = new Segment<>(
                file,
                storageMode,
                dataLength,
                new BlockSerializer(codec, blockFactory),
                bufferPool,
                writeMode,
                checksumProb,
                readPageSize,
                this::processEntries,
                this::writeFooter);

        this.footerReader = delegate.footerReader();
        if (delegate.readOnly()) {
            this.info = footerReader.read(BLOCK_INFO_FOOTER_ITEM, infoSerializer);
        }
    }

    private void writeFooter(FooterWriter writer) {
        writer.write(BLOCK_INFO_FOOTER_ITEM, info, infoSerializer);
        footerWriter.accept(writer);
    }

    @Override
    public long append(T entry) {
        if (!hasSpaceAvailableForBlock()) {
            if (!writeBlock.isEmpty()) {
                throw new IllegalStateException("Block was not empty");
            }
            return Storage.EOF;
        }

        try (bufferPool) {
            ByteBuffer bb = bufferPool.allocate();
            serializer.writeTo(entry, bb);
            bb.flip();
            if (!writeBlock.add(bb)) {
                bufferPool.free(); //free so it can be used by DataStream
                writeBlock();
                if (!hasSpaceAvailableForBlock()) {
                    return Storage.EOF;
                }

                bb = bufferPool.allocate();
                serializer.writeTo(entry, bb);
                bb.flip();
                if (!writeBlock.add(bb)) {
                    throw new IllegalStateException("Could not write to new block after flushing, block must ensure entry can be written or thrown an error");
                }
            }
            info.addEntryCount(1);
            return delegate.position();
        }
    }

    private boolean hasSpaceAvailableForBlock() {
        long writePos = position();
        long logSize = dataSize();
        return writePos + info.blockSize() < logSize;
    }

    private synchronized void writeBlock() {
        if (writeBlock.isEmpty()) {
            return;
        }

        info.addUncompressedSize(writeBlock.uncompressedSize());
        long blockPos = delegate.append(writeBlock);
        if (blockPos == Storage.EOF) {
            throw new IllegalStateException("Got EOF when writing non empty block");
        }

        this.blockWriteListener.accept(blockPos, writeBlock);
        writeBlock.clear();
    }

    public SegmentIterator<Block> blockIterator(Direction direction) {
        return delegate.iterator(direction);
    }

    public SegmentIterator<Block> blockIterator(long position, Direction direction) {
        return delegate.iterator(position, direction);
    }

    //actual entries present in all blovks
    public long totalEntries() {
        return info.entries();
    }

    public FooterReader footerReader() {
        return footerReader;
    }

    public long blocks() {
        return delegate.entries();
    }

    public Block getBlock(long blockPos) {
        return delegate.get(blockPos);
    }

    public List<T> readBlockEntries(long blockPos) {
        Block block = delegate.get(blockPos);
        if (block == null) {
            return Collections.emptyList();
        }
        return block.deserialize(serializer);
    }

    @Override
    public long physicalSize() {
        return delegate.physicalSize();
    }

    @Override
    public long logicalSize() {
        return delegate.logicalSize();
    }

    @Override
    public long dataSize() {
        return delegate.dataSize();
    }

    @Override
    public long actualDataSize() {
        return delegate.actualDataSize();
    }

    @Override
    public long uncompressedDataSize() {
        return info.uncompressedSize();
    }

    @Override
    public long headerSize() {
        return delegate.headerSize();
    }

    @Override
    public long footerSize() {
        return delegate.footerSize();
    }

    @Override
    public String name() {
        return delegate.name();
    }

    @Override
    public SegmentIterator<T> iterator(long position, Direction direction) {
        return new BlockIterator<>(serializer, delegate.iterator(direction), direction);
    }

    @Override
    public SegmentIterator<T> iterator(Direction direction) {
        return new BlockIterator<>(serializer, delegate.iterator(direction), direction);
    }

    @Override
    public long position() {
        return delegate.position();
    }

    @Override
    public T get(long position) {
        throw new UnsupportedOperationException();
    }

    @Override
    public long remaining() {
        return delegate.remaining();
    }

    @Override
    public void delete() {
        delegate.delete();
    }

    @Override
    public synchronized void flush() {
        if (readOnly()) {
            return;
        }
        writeBlock();
        delegate.flush();
    }

    @Override
    public synchronized void roll(int level, boolean trim) {
        writeBlock();
        delegate.roll(level, trim);
    }

    @Override
    public boolean readOnly() {
        return delegate.readOnly();
    }

    @Override
    public boolean closed() {
        return delegate.closed();
    }

    @Override
    public long entries() {
        return info.entries();
    }

    @Override
    public int level() {
        return delegate.level();
    }

    @Override
    public long created() {
        return delegate.created();
    }

    private int processEntries(List<RecordEntry<Block>> items) {
        int entryCount = 0;
        for (RecordEntry<Block> recordEntry : items) {
            Block block = recordEntry.entry();
            info.addUncompressedSize(block.uncompressedSize());
            info.addCompressedSize(recordEntry.dataSize());
            entryCount += block.entryCount();
            onBlockLoaded.accept(recordEntry.position(), block);
        }
        info.addEntryCount(entryCount);
        return entryCount;
    }

    @Override
    public long uncompressedSize() {
        return info.uncompressedSize();
    }

    @Override
    public Type type() {
        return delegate.type();
    }

    public int blockSize() {
        return info.blockSize();
    }

    public static <T> SegmentFactory<T> factory(Codec codec, int blockSize) {
        return factory(codec, blockSize, Block.vlenBlock(), (a, b) -> {
        }, (l, block) -> {
        }, fWriter -> {
        });
    }

    public static <T> SegmentFactory<T> factory(Codec codec,
                                                int blockSize,
                                                BiConsumer<Long, Block> blockWriteListener,
                                                BiConsumer<Long, Block> onBlockLoaded,
                                                Consumer<FooterWriter> footerWriter) {
        return factory(codec, blockSize, Block.vlenBlock(), blockWriteListener, onBlockLoaded, footerWriter);
    }

    public static <T> SegmentFactory<T> factory(Codec codec,
                                                int blockSize,
                                                BlockFactory blockFactory,
                                                BiConsumer<Long, Block> blockWriteListener,
                                                BiConsumer<Long, Block> onBlockLoaded,
                                                Consumer<FooterWriter> footerWriter) {
        return (file, storageMode, dataLength, serializer, bufferPool, writeMode, checksumProb, readPageSize) -> new BlockSegment<>(file, storageMode, dataLength, bufferPool, writeMode, serializer, blockFactory, codec, blockSize, checksumProb, readPageSize, blockWriteListener, onBlockLoaded, footerWriter);
    }

    @Override
    public void close() {
        flush();
        delegate.close();
    }
}
