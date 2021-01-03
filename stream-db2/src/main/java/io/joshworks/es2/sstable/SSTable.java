package io.joshworks.es2.sstable;

import io.joshworks.es2.SegmentChannel;
import io.joshworks.es2.SegmentFile;
import io.joshworks.es2.index.BTreeIndexSegment;
import io.joshworks.es2.index.IndexEntry;
import io.joshworks.es2.index.IndexFunction;
import io.joshworks.es2.index.IndexWriter;
import io.joshworks.es2.sink.Sink;
import io.joshworks.fstore.core.util.Memory;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.Iterator;

import static io.joshworks.es2.Event.NO_VERSION;

class SSTable implements SegmentFile {

    private static final String INDEX_EXT = "idx";

    public static final int NO_DATA = -11;
    public static final int VERSION_TOO_HIGH = -22;

    private final SegmentChannel data;
    private final BTreeIndexSegment index;

    private SSTable(SegmentChannel data, BTreeIndexSegment index) {
        this.data = data;
        this.index = index;
    }

    static SSTable open(File dataFile) {
        File indexFile = indexFile(dataFile);
        var data = SegmentChannel.open(dataFile);
        var index = BTreeIndexSegment.open(indexFile);
        return new SSTable(data, index);
    }

    public int version(long stream) {
        IndexEntry ie = index.find(stream, Integer.MAX_VALUE, IndexFunction.FLOOR);
        return ie == null ? NO_VERSION : ie.version() + ie.entries() - 1;
    }

    public int get(long stream, int version, Sink sink) {
        IndexEntry ie = index.find(stream, version, IndexFunction.FLOOR);
        if (ie == null) {
            return NO_DATA;
        }

        int recSize = ie.recordSize();
        long logAddress = ie.logAddress();
        int startVersion = ie.version();
        int entries = ie.entries();

        assert startVersion <= version;

        //end version is greater than latest version from index, sstable should not continue searching (considering sstable lookup is older to newer)
        if (version > startVersion + entries - 1) {
            return VERSION_TOO_HIGH;
        }
        //cast is ok since the data transferred is never going to be grater than stream block (~4kb)
        return (int) data.transferTo(logAddress, recSize, sink);
    }

    public IndexEntry get(long stream, int version) {
        return index.find(stream, version, IndexFunction.FLOOR);
    }

    static SSTable create(File dataFile, Iterator<ByteBuffer> items) {
        File indexFile = indexFile(dataFile);

        SegmentChannel dataChannel = SegmentChannel.create(dataFile);
        SegmentChannel indexChannel = SegmentChannel.create(indexFile);

        StreamBlockWriter dataChunkWriter = new StreamBlockWriter(BlockCodec.SNAPPY, Memory.PAGE_SIZE);

        try (IndexWriter indexWriter = new IndexWriter(indexChannel)) {
            while (items.hasNext()) {
                ByteBuffer data = items.next();
                dataChunkWriter.add(data, dataChannel, indexWriter);
            }

            dataChunkWriter.complete(dataChannel, indexWriter);

            dataChannel.truncate();
            indexWriter.complete(); //indexwriter already truncates channel
            return new SSTable(dataChannel, BTreeIndexSegment.open(indexFile));
        }

    }

    private static File indexFile(File dataFile) {
        Path parent = dataFile.toPath().getParent();
        String indexFileName = dataFile.getName().split("\\.")[0] + "." + INDEX_EXT;
        return parent.resolve(indexFileName).toFile();
    }

    @Override
    public void close() {
        data.close();
        index.close();
    }

    @Override
    public void delete() {
        data.delete();
        index.delete();
    }

    @Override
    public String name() {
        return data.name();
    }

    @Override
    public String toString() {
        return data.toString();
    }
}
