package io.joshworks.fstore.lsmtree.sstable;

import io.joshworks.fstore.core.io.Storage;
import io.joshworks.fstore.index.Range;
import io.joshworks.fstore.log.Direction;
import io.joshworks.fstore.log.LogIterator;
import io.joshworks.fstore.log.iterators.Iterators;

import java.util.Iterator;
import java.util.NavigableSet;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.atomic.AtomicInteger;

import static java.util.Objects.requireNonNull;

public class MemTable<K extends Comparable<K>, V> implements TreeFunctions<K, V> {

    private final ConcurrentSkipListSet<Entry<K, V>> table = new ConcurrentSkipListSet<>();
    private final AtomicInteger size = new AtomicInteger();

    public int add(Entry<K, V> entry) {
        requireNonNull(entry, "Entry must be provided");
        boolean added = table.add(entry);
        if (!added) {
            table.remove(entry);
            table.add(entry);
            return size.get();
        }
        return size.incrementAndGet();
    }

    @Override
    public Entry<K, V> get(K key) {
        requireNonNull(key, "Key must be provided");
        Entry<K, V> found = floor(key);
        if (found == null) {
            return null;
        }
        return found.key.equals(key) ? found : null;
    }

    @Override
    public Entry<K, V> floor(K key) {
        return table.floor(Entry.key(key));
    }

    @Override
    public Entry<K, V> ceiling(K key) {
        return table.ceiling(Entry.key(key));
    }

    @Override
    public Entry<K, V> higher(K key) {
        return table.higher(Entry.key(key));
    }

    @Override
    public Entry<K, V> lower(K key) {
        return table.lower(Entry.key(key));
    }

    public Iterator<Entry<K, V>> iterator() {
        return table.iterator();
    }

    public long writeTo(SSTables<K, V> sstables, long maxAge) {
        if (isEmpty()) {
            return 0;
        }

        long inserted = 0;
        for (Entry<K, V> entry : table) {
            if (entry.expired(maxAge)) {
                continue;
            }
            long entryPos = sstables.write(entry);
            inserted++;
            if (entryPos == Storage.EOF) {
                sstables.roll();
            }
        }
        if (inserted > 0) {
            sstables.roll();
        }
        return inserted;
    }

    public LogIterator<Entry<K, V>> iterator(Direction direction, Range<K> range) {
        NavigableSet<Entry<K, V>> subSet = table.subSet(Entry.key(range.start()), Entry.key(range.end()));
        return Direction.BACKWARD.equals(direction) ? Iterators.wrap(subSet.descendingIterator()) : Iterators.of(subSet);
    }

    public int size() {
        return table.size();
    }

    public boolean isEmpty() {
        return table.isEmpty();
    }

}
