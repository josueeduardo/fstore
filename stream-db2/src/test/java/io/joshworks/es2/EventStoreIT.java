package io.joshworks.es2;

import io.joshworks.es2.sink.Sink;
import io.joshworks.es2.sstable.TestEvent;
import io.joshworks.fstore.core.util.TestUtils;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.nio.ByteBuffer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class EventStoreIT {

    private EventStore store;
    private File root;


    @Before
    public void setUp() {
        root = TestUtils.testFolder();
        store = new EventStore(root.toPath());
    }

    @Test
    public void read() throws InterruptedException {

        Thread.sleep(7000);

        int items = 50_000_000;
        String stream = "stream-1";
        TestEvent ev1 = TestEvent.create(stream, Event.NO_VERSION, 0, "type-a", "data-1");
        ByteBuffer data = ev1.serialize();

        //write
        long s = System.currentTimeMillis();
        for (int i = 0; i < items; i++) {
            data.clear();
            Event.writeVersion(data, Event.NO_VERSION);
            store.append(data);

            if (i % 100_000 == 0) {
                System.out.println("WRITE: " + i + " - " + (System.currentTimeMillis() - s) + "ms");
                s = System.currentTimeMillis();
            }
        }

        //read
        read(items, stream);
    }

    private void read(int items, String stream) {
        long s;
        Sink.Memory sink = new Sink.Memory();
        int currVersion = 0;
        s = System.currentTimeMillis();
        do {
            int read = store.read(StreamHasher.hash(stream), currVersion, sink);
            assertTrue(read > 0);

            ByteBuffer readData = ByteBuffer.wrap(sink.data());
            int startVersion = StreamBlock.startVersion(readData);
            int entries = StreamBlock.entries(readData);

            assertEquals(currVersion, startVersion);
            assertTrue(entries > 0);

            currVersion = startVersion + entries;
            sink.close(); //reset buffer

        } while (currVersion < items);
        System.out.println("READ: " + (System.currentTimeMillis() - s));
    }

}