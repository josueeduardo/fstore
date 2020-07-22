package io.joshworks.es;

import io.joshworks.es.async.WriteEvent;
import io.joshworks.es.index.IndexKey;
import io.joshworks.fstore.core.io.buffers.Buffers;
import io.joshworks.fstore.core.util.Size;
import io.joshworks.fstore.core.util.TestUtils;
import io.joshworks.fstore.core.util.Threads;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class EventStoreTest {

    public static final int MEMTABLE_SIZE = 500000;
    private IEventStore store;
    private File root;

    @Before
    public void setUp() {
        root = TestUtils.testFolder();
        store = open();
    }

    private IEventStore open() {
        return new EventStore(root, Size.MB.ofInt(100), MEMTABLE_SIZE, 0.1, 4096);
    }

    @Test
    public void append_get() {
        String stream = "abc-123";

        store.append(create(stream, -1, "CREATE", "abc"));
        store.append(create(stream, -1, "UPDATE", "123"));
        store.append(create(stream, -1, "UPDATE", "123"));
        store.append(create(stream, -1, "UPDATE", "123"));
        store.append(create(stream, -1, "UPDATE", "123"));
        store.append(create(stream, -1, "UPDATE", "123"));

        Threads.sleep(5000);

        store.append(create(stream, -1, "CREATE", "abc"));
        store.append(create(stream, -1, "UPDATE", "123"));
        store.append(create(stream, -1, "UPDATE", "123"));
        store.append(create(stream, -1, "UPDATE", "123"));
        store.append(create(stream, -1, "UPDATE", "123"));
        store.append(create(stream, -1, "UPDATE", "123"));

        Threads.sleep(5000);

        ByteBuffer readBuffer = Buffers.allocate(4096, false);
        int read = store.get(IndexKey.of(stream, 0), readBuffer);
        readBuffer.flip();


        assertTrue(read > 0);
        assertTrue(Event.isValid(readBuffer));
        assertEquals(StreamHasher.hash(stream), Event.stream(readBuffer));
        assertEquals(0, Event.version(readBuffer));
    }

    private static WriteEvent create(String stream, int expectedVersion, String evType, String data) {
        WriteEvent event = new WriteEvent();
        event.stream = stream;
        event.expectedVersion = expectedVersion;
        event.type = evType;
        event.data = data.getBytes(StandardCharsets.UTF_8);
        event.metadata = new byte[0];

        return event;

    }

//    @Test
//    public void append_expected_version() {
//        long stream = 123;
//        int entries = (int) (MEMTABLE_SIZE * 1.5);
//
//        for (int i = 0; i < entries; i++) {
//            store.append(stream, i - 1, wrap("abc"));
//        }
//    }
//
//    @Test
//    public void version() {
//        long stream = 123;
//        int entries = (int) (MEMTABLE_SIZE * 2.5);
//
//        assertEquals(-1, store.version(stream));
//
//        for (int i = 0; i < entries; i++) {
//            store.append(stream, -1, wrap("abc"));
//            assertEquals(i, store.version(stream));
//        }
//    }
//
//    @Test
//    public void linkTo() {
//        long srcStream = 123;
//        long dstStream = 456;
//
//        store.append(srcStream, -1, wrap("abc"));
//        store.linkTo(srcStream, 0, dstStream, -1);
//
//        ByteBuffer readBuffer = Buffers.allocate(4096, false);
//        store.get(dstStream, 0, readBuffer);
//
//        readBuffer.flip();
//        assertTrue(Event.isValid(readBuffer));
//        assertEquals(dstStream, Event.stream(readBuffer));
//        assertEquals(0, Event.version(readBuffer));
//    }
//
    @Test
    public void append_MANY_SAME_STREAM_TEST() {
        String stream = "stream-1";
        int items = 20000000;

        long s = System.currentTimeMillis();
        for (int i = 0; i < items; i++) {
            if (i % 500000 == 0) {
                System.out.println();
            }
            store.append(create(stream, i - 1, "TEST", "abc"));
            if (i % 1000000 == 0) {
                long now = System.currentTimeMillis();
                System.out.println("WRITE: " + i + " -> " + (now - s));
                s = now;
            }
        }

        Threads.sleep(50000);

        ByteBuffer readBuffer = Buffers.allocate(4096, false);
        for (int i = 0; i < items; i++) {
            int read = store.get(IndexKey.of(stream, i), readBuffer.clear());
            readBuffer.flip();

            assertTrue("Failed on " + i, read > 0);
            assertTrue(Event.isValid(readBuffer));
            assertEquals(StreamHasher.hash(stream), Event.stream(readBuffer));
            assertEquals(i, Event.version(readBuffer));
            if (i % 1000000 == 0) {
                long now = System.currentTimeMillis();
                System.out.println("READ: " + i + " -> " + (now - s));
                s = now;
            }
        }


    }


}