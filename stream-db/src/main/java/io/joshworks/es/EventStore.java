package io.joshworks.es;

import io.joshworks.es.index.Index;
import io.joshworks.es.index.IndexEntry;
import io.joshworks.es.index.IndexKey;
import io.joshworks.es.log.Log;
import io.joshworks.es.writer.WriteEvent;
import io.joshworks.es.writer.WriterThread;
import io.joshworks.fstore.core.io.buffers.Buffers;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

public class EventStore {

    private final Log log;
    private final Index index;
    private final WriterThread writerThread;

    private static ThreadLocal<ByteBuffer> pageBufferCache = ThreadLocal.withInitial(() -> Buffers.allocate(4096, false));

    public EventStore(File root, int logSize, int indexEntries, int blockSize, int versionCacheSize) {
        this.log = new Log(root, logSize);
        this.index = new Index(root, indexEntries, blockSize, versionCacheSize);
        this.writerThread = new WriterThread(log, index, 100, 4096, 100);
        this.writerThread.start();
    }

    public int version(long stream) {
        return index.version(stream);
    }

    public synchronized void linkTo(String srcStream, int srcVersion, String dstStream, int expectedVersion) {
        writerThread.submit(writer -> {

            long srcStreamHash = StreamHasher.hash(srcStream);
            long dstStreamHash = StreamHasher.hash(dstStream);

            IndexEntry ie = writer.findEquals(new IndexKey(srcStreamHash, srcVersion));
            if (ie == null) {
                throw new IllegalArgumentException("No such event " + IndexKey.toString(srcStream, srcVersion));
            }
            int dstVersion = writer.nextVersion(dstStreamHash, expectedVersion);

            WriteEvent linkToEvent = createLinkToEvent(srcStream, srcVersion, dstStream, dstVersion);

            long logAddress = writer.appendToLog(linkToEvent);

            writer.adToIndex(new IndexEntry(dstStreamHash, dstVersion, logAddress));
        });
    }

    private WriteEvent createLinkToEvent(String srcStream, int srcVersion, String dstStream, int dstVersion) {
        WriteEvent linktoEv = new WriteEvent();
        linktoEv.stream = dstStream;
        linktoEv.version = dstVersion;
        linktoEv.type = "LINK_TO";
        linktoEv.timestamp = System.currentTimeMillis();
        linktoEv.metadata = new byte[0];
        linktoEv.data = IndexKey.toString(srcStream, srcVersion).getBytes(StandardCharsets.UTF_8);
        //TODO add linkTo attribute ?
        return linktoEv;
    }

    public void append(WriteEvent event) {
        var result = writerThread.submit(writer -> {
            long stream = StreamHasher.hash(event.stream);
            int version = writer.nextVersion(stream, event.expectedVersion);
            event.version = version;

            long logAddress = writer.appendToLog(event);
            writer.adToIndex(new IndexEntry(stream, version, logAddress));
        });

    }

    public int get(IndexKey key, ByteBuffer dst) {
        ByteBuffer pageBuffer = pageBufferCache.get().clear();
        QueryPlanner planner = new QueryPlanner();
        boolean success = planner.prepare(index, key, 100, pageBuffer);
        if (!success) {
            return 0;
        }

        return planner.execute(log, dst);
    }

    public void flush() {
        index.flush();
    }
}
