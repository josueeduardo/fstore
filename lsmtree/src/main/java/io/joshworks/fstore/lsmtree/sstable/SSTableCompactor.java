package io.joshworks.fstore.lsmtree.sstable;

import io.joshworks.fstore.log.appender.compaction.combiner.UniqueMergeCombiner;
import io.joshworks.fstore.lsmtree.EntryType;

public class SSTableCompactor<K extends Comparable<K>, V> extends UniqueMergeCombiner<Entry<K, V>> {

    @Override
    public boolean filter(Entry<K, V> entry) {
        return EntryType.ADD.equals(entry.type);
    }
}
