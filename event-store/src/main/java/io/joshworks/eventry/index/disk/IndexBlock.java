package io.joshworks.eventry.index.disk;

import io.joshworks.eventry.index.IndexEntry;
import io.joshworks.fstore.core.Codec;
import io.joshworks.fstore.log.segment.block.Block;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;


/**
 * Format:
 * |streamHash entryCount | version1 pos1 | version2 pos2 | versionN posN |
 *
 * Where:
 * streamHash - 64bits
 * entryCount - 32bits
 * version - 32bits
 * pos - 64bits
 *
 * Definitions:
 * streamHash: any real number
 * entryCount: greater than zero
 * pos: greater or equal zero
 *
 * version: positive greater or equal zero for insertions, negative value representing the truncated before version
 * of the it's absolute value, example... -10 represents truncated versions from version 0 until 10.
 * So only version 11 onwards will be available.
 *
 * Deleting a stream means the truncated version will be Integer.MIN_VALUE
 *
 *
 *
 */
public class IndexBlock implements Block<IndexEntry> {

    private final List<IndexEntry> cached = new ArrayList<>();
    private final int maxSize;

    IndexBlock(int maxSize) {
        this.maxSize = maxSize;
    }

    IndexBlock(ByteBuffer data, Codec codec) {
        this.cached.addAll(unpack(data, codec));
        this.maxSize = -1;
    }

    @Override
    public boolean add(IndexEntry data) {
        if (readOnly()) {
            throw new IllegalStateException("Block is read only");
        }
        cached.add(data);
        return cached.size() * IndexEntry.BYTES >= maxSize;
    }

    @Override
    public ByteBuffer pack(Codec codec) {
        if (cached.isEmpty()) {
            return ByteBuffer.allocate(0);
        }
        int maxVersionSizeOverhead = entryCount() * Integer.BYTES;
        int actualSize = IndexEntry.BYTES * entryCount();
        var packedBuffer = ByteBuffer.allocate(actualSize + maxVersionSizeOverhead);

        IndexEntry last = null;
        List<Integer> versions = new ArrayList<>();
        List<Long> positions = new ArrayList<>();
        for (IndexEntry indexEntry : cached) {
            if (last == null) {
                last = indexEntry;
            }
            if (last.stream != indexEntry.stream) {
                writeToBuffer(packedBuffer, last.stream, versions, positions);
                versions = new ArrayList<>();
                positions = new ArrayList<>();
            }

            versions.add(indexEntry.version);
            positions.add(indexEntry.position);
            last = indexEntry;
        }
        if(last != null && !versions.isEmpty()) {
            writeToBuffer(packedBuffer, last.stream, versions, positions);
        }

        packedBuffer.flip();
        return codec.compress(packedBuffer);
    }

    private void writeToBuffer(ByteBuffer buffer, long stream, List<Integer> versions, List<Long> positions) {
        buffer.putLong(stream);
        buffer.putInt(versions.size());
        for (int i = 0; i < versions.size(); i++) {
            buffer.putInt(versions.get(i));
            buffer.putLong(positions.get(i));
        }
    }

    private List<IndexEntry> unpack(ByteBuffer readBuffer, Codec codec) {
        ByteBuffer decompressed = codec.decompress(readBuffer);
        List<IndexEntry> entries = new ArrayList<>();
        while (decompressed.hasRemaining()) {
            long stream = decompressed.getLong();
            int numVersions = decompressed.getInt();
            for (int i = 0; i < numVersions; i++) {
                int version = decompressed.getInt();
                long position = decompressed.getLong();
                entries.add(IndexEntry.of(stream, version, position));
            }
        }
        return entries;
    }

    @Override
    public int entryCount() {
        return cached.size();
    }

    @Override
    public List<IndexEntry> entries() {
        return new ArrayList<>(cached);
    }

    @Override
    public IndexEntry first() {
        if (cached.isEmpty()) {
            return null;
        }
        return cached.get(0);
    }

    @Override
    public IndexEntry last() {
        if (cached.isEmpty()) {
            return null;
        }
        return cached.get(cached.size() - 1);
    }

    @Override
    public IndexEntry get(int pos) {
        return cached.get(pos);
    }

    @Override
    public boolean readOnly() {
        return false;
    }

    @Override
    public boolean isEmpty() {
        return cached.isEmpty();
    }

    @Override
    public List<Integer> entriesLength() {
        return cached.stream().map(i -> IndexEntry.BYTES).collect(Collectors.toList());
    }
}
