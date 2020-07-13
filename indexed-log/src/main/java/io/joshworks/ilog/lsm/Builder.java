package io.joshworks.ilog.lsm;

import io.joshworks.fstore.core.RuntimeIOException;
import io.joshworks.fstore.core.codec.Codec;
import io.joshworks.fstore.core.util.Size;
import io.joshworks.ilog.index.RowKey;
import io.joshworks.ilog.record.PoolConfig;
import io.joshworks.ilog.record.RecordPool;

import java.io.File;

public class Builder {
    private final File root;
    private final RowKey comparator;
    private long maxAge = -1;

    private int compactionThreads = 1;
    private int compactionThreshold = 2;

    private int memTableMaxSizeInBytes = Size.MB.ofInt(5);
    private int memTableMaxEntries = 500000;
    private final PoolConfig pool = RecordPool.create();

    public Builder(File root, RowKey comparator) {
        this.root = root;
        this.comparator = comparator;
    }

    public Builder memTable(int maxEntries, int maxSize) {
        if (maxEntries <= 0) {
            throw new IllegalArgumentException("maxEntries must be a positive number");
        }
        this.memTableMaxSizeInBytes = maxSize;
        this.memTableMaxEntries = maxEntries;
        return this;
    }

    public Builder compactionThreshold(int compactionThreshold) {
        this.compactionThreshold = compactionThreshold;
        return this;
    }

    public Builder compactionThreads(int compactionThreads) {
        if (compactionThreads < 1) {
            throw new IllegalArgumentException("Value must equals or greater than 1");
        }
        this.compactionThreads = compactionThreads;
        return this;
    }

    public Builder maxAge(long maxAge) {
        this.maxAge = maxAge;
        return this;
    }

    /**
     * Create a new sparse LSMTree
     */
    public Lsm sparse(Codec codec, int blockSize) {
        return new SparseLsm(
                root,
                pool.build(),
                comparator,
                memTableMaxSizeInBytes,
                memTableMaxEntries,
                maxAge,
                compactionThreads,
                compactionThreshold,
                blockSize,
                codec);
    }

    /**
     * Create a new dense LSMTree
     */
    public Lsm open() {
        try {
            return new Lsm(
                    root,
                    pool.build(),
                    comparator,
                    memTableMaxSizeInBytes,
                    memTableMaxEntries,
                    maxAge,
                    compactionThreads,
                    compactionThreshold);

        } catch (Exception e) {
            throw new RuntimeIOException("Failed to create LSM", e);
        }
    }
}
