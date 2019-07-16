package io.joshworks.fstore.lsmtree.sstable;

import io.joshworks.fstore.core.Codec;
import io.joshworks.fstore.core.Serializer;
import io.joshworks.fstore.core.io.StorageMode;
import io.joshworks.fstore.core.io.buffers.BufferPool;
import io.joshworks.fstore.index.Range;
import io.joshworks.fstore.index.cache.Cache;
import io.joshworks.fstore.index.cache.LRUCache;
import io.joshworks.fstore.index.cache.NoCache;
import io.joshworks.fstore.index.filter.BloomFilter;
import io.joshworks.fstore.index.midpoints.Midpoint;
import io.joshworks.fstore.index.midpoints.Midpoints;
import io.joshworks.fstore.log.Direction;
import io.joshworks.fstore.log.SegmentIterator;
import io.joshworks.fstore.log.segment.Log;
import io.joshworks.fstore.log.segment.SegmentFactory;
import io.joshworks.fstore.log.segment.WriteMode;
import io.joshworks.fstore.log.segment.block.Block;
import io.joshworks.fstore.log.segment.block.BlockFactory;
import io.joshworks.fstore.log.segment.block.BlockSegment;
import io.joshworks.fstore.log.segment.footer.FooterReader;
import io.joshworks.fstore.log.segment.footer.FooterWriter;
import io.joshworks.fstore.log.segment.header.Type;
import io.joshworks.fstore.serializer.Serializers;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.List;

import static io.joshworks.fstore.lsmtree.sstable.EntrySerializer.KEY_START_POS;
import static java.util.Objects.requireNonNull;

/**
 * A clustered index, key and values are kept together in the data area.
 * Midpoints and Bloom filter are kep in the footer area.
 * This segment uses {@link BlockSegment} as the underlying storage. Therefore scan performed on a non readOnly is not permitted
 * block data that hasn't been persisted to disk, append will be buffered as it has to write to block first.
 * Any data that is not persisted will be lost when the store is closed or if the system crash.
 * This segment is intended to be used as persistent backend of an index, in which data is first stored in some sort
 * of transaction log, then added to this index so in case of failure this index can be rebuilt from the log
 *
 * @param <K> The Key type
 * @param <V> The value type
 */
public class SSTable<K extends Comparable<K>, V> implements Log<Entry<K, V>> {

    private static final String MIDPOINT_BLOCK = "MIDPOINT";
    private static final String BLOOM_FILTER_BLOCK = "BLOOM_FILTER";

    private final BlockSegment<Entry<K, V>> delegate;
    private final Serializer<Entry<K, V>> entrySerializer;
    private final Serializer<K> keySerializer;

    private BloomFilter<K> bloomFilter;
    private Midpoints<K> midpoints;

    private final Cache<Long, Block> blockCache;

    public SSTable(File file,
                   StorageMode storageMode,
                   long segmentDataSize,
                   Serializer<K> keySerializer,
                   Serializer<V> valueSerializer,
                   BufferPool bufferPool,
                   WriteMode writeMode,
                   BlockFactory blockFactory,
                   Codec codec,
                   long bloomNItems,
                   double bloomFPProb,
                   int blockSize,
                   int blockCacheSize,
                   int blockCacheMaxAge,
                   double checksumProb,
                   int readPageSize) {

        this.keySerializer = keySerializer;
        this.entrySerializer = new EntrySerializer<>(keySerializer, valueSerializer);

        this.bloomFilter = BloomFilter.create(bloomNItems, bloomFPProb, keySerializer);
        this.midpoints = new Midpoints<>();
        this.blockCache = blockCacheSize > 0 ? new LRUCache<>(blockCacheSize, blockCacheMaxAge) : new NoCache<>();

        this.delegate = new BlockSegment<>(
                file,
                storageMode,
                segmentDataSize,
                bufferPool,
                writeMode,
                entrySerializer,
                blockFactory,
                codec,
                blockSize,
                checksumProb,
                readPageSize,
                this::onBlockWrite,
                this::onBlockLoaded,
                this::writeFooter);

        if (delegate.readOnly()) {
            FooterReader reader = delegate.footerReader();
            ByteBuffer blockData = reader.read(MIDPOINT_BLOCK, Serializers.COPY);
            if (blockData != null) {
                this.midpoints = Midpoints.load(blockData, keySerializer);
            }

            ByteBuffer bfData = reader.read(BLOOM_FILTER_BLOCK, Serializers.COPY);
            if (blockData != null) {
                this.bloomFilter = BloomFilter.load(bfData, keySerializer);
            }
        }
    }

    private void onBlockLoaded(long position, Block block) {
        addToMidpoints(position, block);
        List<Entry<K, V>> entries = block.deserialize(entrySerializer);
        for (Entry<K, V> entry : entries) {
            bloomFilter.add(entry.key);
        }
    }

    private void onBlockWrite(long position, Block block) {
        addToMidpoints(position, block);
    }

    private void addToMidpoints(long position, Block block) {
        Entry<K, V> first = entrySerializer.fromBytes(block.first());
        Entry<K, V> last = entrySerializer.fromBytes(block.last());

        Midpoint<K> start = new Midpoint<>(first.key, position);
        Midpoint<K> end = new Midpoint<>(last.key, position);

        midpoints.add(start, end);
    }

    private void writeFooter(FooterWriter writer) {
        ByteBuffer midpointData = midpoints.serialize(keySerializer);
        writer.write(MIDPOINT_BLOCK, midpointData);

        ByteBuffer bloomFilterData = bloomFilter.serialize();
        writer.write(BLOOM_FILTER_BLOCK, bloomFilterData);
    }

    @Override
    public long append(Entry<K, V> data) {
        requireNonNull(data, "Entry must be provided");
        requireNonNull(data.key, "Entry Key must be provided");
        bloomFilter.add(data.key);
        return delegate.append(data);
    }

    @Override
    public Entry<K, V> get(long position) {
        if (!readOnly()) {
            throw new IllegalStateException("Cannot read from a open segment");
        }
        return delegate.get(position);
    }

    public V get(K key) {
        if (!readOnly()) {
            throw new IllegalStateException("Cannot read from a open segment");
        }
        if (!bloomFilter.contains(key)) {
            return null;
        }
        Midpoint<K> midpoint = midpoints.getMidpointFor(key);
        if (midpoint == null) {
            return null;
        }

        return readFromBlock(key, midpoint);
    }

    public K firstKey() {
        return midpoints.first().key;
    }

    public K lastKey() {
        return midpoints.last().key;
    }

    public Entry<K, V> first() {
        return getAt(midpoints.first(), true);
    }

    public Entry<K, V> last() {
        return getAt(midpoints.last(), false);
    }

    //firstLast is a hacky way of getting either the first or last block element
    //true if first block element
    //false if last block element
    private Entry<K, V> getAt(Midpoint<K> midpoint, boolean firstLast) {
        Block block = delegate.getBlock(midpoint.position);
        ByteBuffer lastEntry = firstLast ? block.first() : block.last();
        return entrySerializer.fromBytes(lastEntry);
    }

    /**
     * Returns the greatest element in this set less than or equal to
     * the given element, or {@code null} if there is no such element.
     *
     * @param key the value to match
     * @return the greatest element less than or equal to {@code e},
     * or {@code null} if there is no such element
     */
    public Entry<K, V> floor(K key) {
        Midpoint<K> midpoint = midpoints.floor(key);
        Block block = delegate.getBlock(midpoint.position);

        List<ByteBuffer> entries = block.entries();
        for (int i = 0; i < entries.size(); i++) {
            ByteBuffer entryData = entries.get(i);
            int compare = compareKey(entryData, key);
            if (compare < 0) { //key is less than
                if (i == 0) {//less than first entry, definitely not in this segment
                    return null;
                }
                //key is less than current item, and there's previous one, return previous one
                ByteBuffer floorEntry = entries.get(i - 1);
                floorEntry.clear();
                return entrySerializer.fromBytes(floorEntry);
            }
            if (compare == 0) { //key equals to current item, return it
                ByteBuffer floorEntry = entries.get(i);
                floorEntry.clear();
                return entrySerializer.fromBytes(floorEntry);
            }
            //key greater, continue...
        }
        //greater than last block entry, definitely last block entry
        return entrySerializer.fromBytes(block.last());
    }

    /**
     * Returns the least element in this set greater than or equal to
     * the given element, or {@code null} if there is no such element.
     *
     * @param key the value to match
     * @return the least element greater than or equal to {@code e},
     * or {@code null} if there is no such element
     */
    public Entry<K, V> ceiling(K key) {
        //less or equals first entry, definitely first entry
        if (key.compareTo(midpoints.first().key) <= 0) {
            return first();
        }
        //in range
        int midpointIdx = midpoints.getMidpointIdx(key);
        Midpoint<K> midpoint = midpoints.getMidpoint(midpointIdx);
        Block block = delegate.getBlock(midpoint.position);
        Entry<K, V> ceiling = ceilingEntry(key, block);
        if (ceiling != null) {
            return ceiling;
        }

        //not in block get next one
        if (midpointIdx == midpoints.size() - 1) { //last midpoint, nothing in this segment
            return null;
        }
        //has next midpoint, fetch first entry of next block
        Midpoint<K> next = midpoints.getMidpoint(midpointIdx + 1);
        Block nextBlock = delegate.getBlock(next.position);
        return entrySerializer.fromBytes(nextBlock.first());
    }

    private Entry<K, V> ceilingEntry(K key, Block block) {
        List<ByteBuffer> entries = block.entries();
        for (int i = entries.size() - 1; i >= 0; i--) {
            ByteBuffer entryData = entries.get(i);
            int compare = compareKey(entryData, key);
            if (compare > 0) { //key is greater tha current
                //not in this block, is in the next get first entry of next one
                //returning null will cause caller to get next block
                if (i == entries.size() - 1) {
                    return null;
                }
                ByteBuffer floorEntry = entries.get(i + 1);
                floorEntry.clear();
                return entrySerializer.fromBytes(floorEntry);
            }
            if (compare == 0) { //key equals to current item, return it
                ByteBuffer floorEntry = entries.get(i);
                floorEntry.clear();
                return entrySerializer.fromBytes(floorEntry);
            }
        }
        //less than first block entry, definitely first block entry
        return entrySerializer.fromBytes(block.first());
    }

    private Entry<K, V> higherEntry(K key, Block block) {
        List<ByteBuffer> entries = block.entries();
        for (int i = entries.size() - 1; i >= 0; i--) {
            ByteBuffer entryData = entries.get(i);
            int compare = compareKey(entryData, key);
            if (compare >= 0) { //key is greater tha current
                //not in this block, is in the next get first entry of next one
                //returning null will cause caller to get next block
                if (i == entries.size() - 1) {
                    return null;
                }
                ByteBuffer floorEntry = entries.get(i + 1);
                floorEntry.clear();
                return entrySerializer.fromBytes(floorEntry);
            }
        }
        //less than first block entry, definitely first block entry
        return entrySerializer.fromBytes(block.first());
    }

    /**
     * Returns the least element in this set strictly greater than the
     * given element, or {@code null} if there is no such element.
     *
     * @param key the value to match
     * @return the least element greater than {@code e},
     * or {@code null} if there is no such element
     */
    public Entry<K, V> higher(K key) {
        //less than first entry, definitely first entry
        if (key.compareTo(midpoints.first().key) < 0) {
            return first();
        }
        //in range
        int midpointIdx = midpoints.getMidpointIdx(key);
        Midpoint<K> midpoint = midpoints.getMidpoint(midpointIdx);
        Block block = delegate.getBlock(midpoint.position);
        Entry<K, V> ceiling = higherEntry(key, block);
        if (ceiling != null) {
            return ceiling;
        }

        //not in block get next one
        if (midpointIdx == midpoints.size() - 1) { //last midpoint, nothing in this segment
            return null;
        }
        //has next midpoint, fetch first entry of next block
        Midpoint<K> next = midpoints.getMidpoint(midpointIdx + 1);
        Block nextBlock = delegate.getBlock(next.position);
        return entrySerializer.fromBytes(nextBlock.first());
    }

    /**
     * Returns the greatest element in this set strictly less than the
     * given element, or {@code null} if there is no such element.
     *
     * @param key the value to match
     * @return the greatest element less than {@code e},
     * or {@code null} if there is no such element
     */
    public Entry<K, V> lower(K key) {
        int idx = midpoints.binarySearch(key);
        //exact match with the first element of the block, get last element from the previous block
        //only do this for block before the last one, as the last two midpoints are the same block
        if (idx > 0 && idx < midpoints.size() - 1) {
            Midpoint<K> midpoint = midpoints.getMidpoint(idx - 1);
            Block block = delegate.getBlock(midpoint.position);
            return entrySerializer.fromBytes(block.last());
        }
        idx = idx < 0 ? Math.abs(idx) - 2 : idx;
        Midpoint<K> midpoint = midpoints.getMidpoint(idx);
        Block block = delegate.getBlock(midpoint.position);

        List<ByteBuffer> entries = block.entries();
        for (int i = 0; i < entries.size(); i++) {
            ByteBuffer entryData = entries.get(i);
            int compare = compareKey(entryData, key);
            if (compare <= 0) { //key is less than or equals
                if (i == 0) {//less than first entry, definitely not in this segment
                    return null;
                }
                //key is less than current item, and there's previous one, return previous one
                ByteBuffer floorEntry = entries.get(i - 1);
                floorEntry.clear();
                return entrySerializer.fromBytes(floorEntry);
            }
            //key greater or equals, continue...
        }
        //greater than last block entry, definitely last block entry
        return entrySerializer.fromBytes(block.last());
    }

    private V readFromBlock(K key, Midpoint<K> midpoint) {
        Block cached = blockCache.get(midpoint.position);
        if (cached == null) {
            Block block = delegate.getBlock(midpoint.position);
            if (block == null) {
                return null;
            }
            blockCache.add(midpoint.position, block);
            return findExact(key, block);
        }
        return findExact(key, cached);
    }

    private V findExact(K key, Block block) {
//        ByteBuffer keyBytes = keySerializer.toBytes(key);
        for (ByteBuffer entryData : block.entries()) {
            if (compareKey(entryData, key) == 0) {
                entryData.clear();
                Entry<K, V> entry = entrySerializer.fromBytes(entryData);
                return entry.value;
            }
        }
        return null;
    }

    private int compareKey(ByteBuffer entry, K key) {
        entry.position(KEY_START_POS);
        K entryKey = keySerializer.fromBytes(entry);

        return key.compareTo(entryKey);
    }

    public SegmentIterator<Entry<K, V>> iterator(Direction direction, Range<K> range) {
        if (!readOnly()) {
            throw new IllegalStateException("Cannot read from a open segment");
        }
        if ((range.start() != null && !midpoints.inRange(range.start())) && (range.end() != null && !midpoints.inRange(range.end()))) {
            return SegmentIterator.empty();
        }

        long startPos = startPos(direction, range);
        SegmentIterator<Entry<K, V>> iterator = iterator(startPos, direction);
        return new RangeIterator<>(range, direction, iterator);

    }

    private long startPos(Direction direction, Range<K> range) {
        if (Direction.FORWARD.equals(direction)) {
            Midpoint<K> mStart = range.start() == null ? midpoints.first() : midpoints.getMidpointFor(range.start());
            return mStart == null ? midpoints.first().position : mStart.position;
        }
        Midpoint<K> mEnd = range.end() == null ? midpoints.last() : midpoints.getMidpointFor(range.end());
        return mEnd == null ? midpoints.last().position : mEnd.position;

    }

    @Override
    public String name() {
        return delegate.name();
    }

    @Override
    public SegmentIterator<Entry<K, V>> iterator(long position, Direction direction) {
        return delegate.iterator(position, direction);
    }

    @Override
    public SegmentIterator<Entry<K, V>> iterator(Direction direction) {
        return delegate.iterator(direction);
    }

    @Override
    public long position() {
        return delegate.position();
    }

    @Override
    public long fileSize() {
        return delegate.fileSize();
    }

    @Override
    public long logSize() {
        return delegate.logSize();
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
    public void roll(int level) {
        delegate.roll(level);
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
        return delegate.entries();
    }

    @Override
    public int level() {
        return delegate.level();
    }

    @Override
    public long created() {
        return delegate.created();
    }

    @Override
    public void trim() {
        delegate.trim();
    }

    @Override
    public long uncompressedSize() {
        return delegate.uncompressedSize();
    }

    @Override
    public Type type() {
        return delegate.type();
    }

    @Override
    public void flush() {
        delegate.flush();
    }

    @Override
    public void close() {
        delegate.close();
    }

    @Override
    public String toString() {
        return name();
    }

    static class SSTableFactory<K extends Comparable<K>, V> implements SegmentFactory<Entry<K, V>> {
        private final Serializer<K> keySerializer;
        private final Serializer<V> valueSerializer;
        private final BlockFactory blockFactory;
        private final Codec codec;
        private final long bloomNItems;
        private final double bloomFPProb;
        private final int blockSize;
        private final int blockCacheSize;
        private final int blockCacheMaxAge;

        SSTableFactory(Serializer<K> keySerializer,
                       Serializer<V> valueSerializer,
                       BlockFactory blockFactory,
                       Codec codec,
                       long bloomNItems,
                       double bloomFPProb,
                       int blockSize,
                       int blockCacheSize,
                       int blockCacheMaxAge) {

            this.keySerializer = keySerializer;
            this.valueSerializer = valueSerializer;
            this.blockFactory = blockFactory;
            this.codec = codec;
            this.bloomNItems = bloomNItems;
            this.bloomFPProb = bloomFPProb;
            this.blockSize = blockSize;
            this.blockCacheSize = blockCacheSize;
            this.blockCacheMaxAge = blockCacheMaxAge;
        }

        @Override
        public Log<Entry<K, V>> createOrOpen(File file, StorageMode storageMode, long dataLength, Serializer<Entry<K, V>> serializer, BufferPool bufferPool, WriteMode writeMode, double checksumProb, int readPageSize) {
            return new SSTable<>(
                    file,
                    storageMode,
                    dataLength,
                    keySerializer,
                    valueSerializer,
                    bufferPool,
                    writeMode,
                    blockFactory,
                    codec,
                    bloomNItems,
                    bloomFPProb,
                    blockSize,
                    blockCacheSize,
                    blockCacheMaxAge,
                    checksumProb,
                    readPageSize);
        }
    }

}
