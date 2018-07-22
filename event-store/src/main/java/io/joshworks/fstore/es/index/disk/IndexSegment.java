package io.joshworks.fstore.es.index.disk;

import io.joshworks.fstore.core.Serializer;
import io.joshworks.fstore.core.io.DataReader;
import io.joshworks.fstore.core.io.Storage;
import io.joshworks.fstore.core.util.Iterators;
import io.joshworks.fstore.es.index.Index;
import io.joshworks.fstore.es.index.IndexEntry;
import io.joshworks.fstore.es.index.Range;
import io.joshworks.fstore.es.index.filter.BloomFilter;
import io.joshworks.fstore.es.index.midpoint.Midpoint;
import io.joshworks.fstore.es.index.midpoint.Midpoints;
import io.joshworks.fstore.index.filter.Hash;
import io.joshworks.fstore.log.LogIterator;
import io.joshworks.fstore.log.segment.Type;
import io.joshworks.fstore.log.segment.block.BlockSegment;
import io.joshworks.fstore.log.segment.block.FixedSizeEntryBlock;
import io.joshworks.fstore.serializer.Serializers;

import java.io.File;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.stream.Stream;

public class IndexSegment extends BlockSegment<IndexEntry, FixedSizeEntryBlock<IndexEntry>> implements Index {

    BloomFilter<Long> filter;
    final Midpoints midpoints;
    final File directory;

    private static final double FALSE_POSITIVE_PROB = 0.01;

    IndexSegment(Storage storage,
                        Serializer<FixedSizeEntryBlock<IndexEntry>> serializer,
                        DataReader reader,
                        Type type,
                        File directory,
                        int numElements) {
        super(storage, serializer, reader, type);
        this.directory = directory;
        this.midpoints = new Midpoints(directory, name());
        this.filter = BloomFilter.openOrCreate(directory, name(), numElements, FALSE_POSITIVE_PROB, new Hash.Murmur64<>(Serializers.LONG));
    }

    @Override
    protected long writeBlock() {
        FixedSizeEntryBlock<IndexEntry> block = currentBlock();
        long position = position();
        if (block.isEmpty()) {
            return position;
        }

        Midpoint head = new Midpoint(block.first(), position);
        Midpoint tail = new Midpoint(block.last(), position);
        midpoints.add(head, tail);

        return super.writeBlock();
    }

    @Override
    public long append(IndexEntry data) {
        filter.add(data.stream);
        return super.append(data);
    }

    @Override
    public void flush() {
        super.flush(); //flush super first, so writeBlock is called
        midpoints.write();
        filter.write();
    }

    @Override
    public void delete() {
        super.delete();
        filter.delete();
        midpoints.delete();
    }

    @Override
    public Iterator<IndexEntry> iterator(Range range) {
        if (!mightHaveEntries(range)) {
            return Iterators.empty();
        }

        Midpoint lowBound = midpoints.getMidpointFor(range.start());
        if (lowBound == null) {
            return Iterators.empty();
        }

        LogIterator<IndexEntry> logIterator = iterator(lowBound.position);
        return new RangeIndexEntryIterator(range, logIterator);
    }

    void newBloomFilter(long numElements) {
        this.filter = BloomFilter.openOrCreate(directory, name(), numElements, FALSE_POSITIVE_PROB, new Hash.Murmur64<>(Serializers.LONG));
    }

    private boolean mightHaveEntries(Range range) {
        return midpoints.inRange(range) && filter.contains(range.stream);
    }


    @Override
    public Stream<IndexEntry> stream(Range range) {
        return Iterators.stream(iterator(range));
    }

    @Override
    public Optional<IndexEntry> get(long stream, int version) {
        Range range = Range.of(stream, version, version + 1);
        if (!mightHaveEntries(range)) {
            return Optional.empty();
        }

        IndexEntry start = range.start();
        Midpoint lowBound = midpoints.getMidpointFor(start);
        if (lowBound == null) {//false positive on the bloom filter and entry was within range of this segment
            return Optional.empty();
        }

        FixedSizeEntryBlock<IndexEntry> block = getBlock(lowBound.position);
        List<IndexEntry> entries = block.entries();
        int idx = Collections.binarySearch(entries, start);
        if(idx < 0) { //if not exact match, wasn't found
            return Optional.empty();
        }
        IndexEntry found = entries.get(idx);
        if (found == null || found.stream != stream && found.version != version) { //sanity check
            throw new IllegalStateException("Inconsistent index");
        }
        return Optional.of(found);

    }

    @Override
    public int version(long stream) {
        Range range = Range.allOf(stream);
        if (!mightHaveEntries(range)) {
            return 0;
        }

        IndexEntry end = range.end();
        Midpoint lowBound = midpoints.getMidpointFor(end);
        if (lowBound == null) {//false positive on the bloom filter and entry was within range of this segment
            return 0;
        }

        FixedSizeEntryBlock<IndexEntry> block = getBlock(lowBound.position);
        List<IndexEntry> entries = block.entries();
        int idx = Collections.binarySearch(entries, end);
        idx = idx >= 0 ? idx : Math.abs(idx) - 2;
        IndexEntry lastVersion = entries.get(idx);
        if (lastVersion.stream != stream) { //false positive on the bloom filter
            return 0;
        }
        return lastVersion.version;
    }

    @Override
    protected FixedSizeEntryBlock<IndexEntry> createBlock() {
        return new FixedSizeEntryBlock<>(new IndexEntrySerializer(), 204, IndexEntry.BYTES);
    }

    private static final class RangeIndexEntryIterator implements Iterator<IndexEntry> {

        private final IndexEntry end;
        private final IndexEntry start;
        private final LogIterator<IndexEntry> segmentIterator;
        private IndexEntry current;

        private RangeIndexEntryIterator(Range range, LogIterator<IndexEntry> logIterator) {
            this.end = range.end();
            this.start = range.start();
            this.segmentIterator = logIterator;

            //initial load skipping less than queuedTime
            while (logIterator.hasNext()) {
                IndexEntry next = logIterator.next();
                if (next.greatOrEqualsTo(start)) {
                    current = next;
                    break;
                }
            }
        }

        @Override
        public boolean hasNext() {
            return current != null && current.lessThan(end);
        }

        @Override
        public IndexEntry next() {
            if (current == null) {
                throw new NoSuchElementException();
            }
            IndexEntry curr = current;
            current = segmentIterator.hasNext() ? segmentIterator.next() : null;
            return curr;
        }
    }

}
