package io.joshworks.fstore.log.appender;

import io.joshworks.fstore.core.io.IOUtils;
import io.joshworks.fstore.core.io.Mode;
import io.joshworks.fstore.core.io.RafStorage;
import io.joshworks.fstore.core.io.Storage;
import io.joshworks.fstore.log.Log;
import io.joshworks.fstore.log.LogIterator;
import io.joshworks.fstore.log.Utils;
import io.joshworks.fstore.serializer.Serializers;
import io.joshworks.fstore.serializer.StringSerializer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

public class LogAppenderTest {

    private static final int SEGMENT_SIZE = 1024 * 64;//64kb

    private SimpleLogAppender<String> appender;
    private File testDirectory;

    @Before
    public void setUp() {
        testDirectory = Utils.testFolder();
        testDirectory.deleteOnExit();

        Builder<String> builder = new Builder<>(testDirectory, new StringSerializer())
                .segmentSize(SEGMENT_SIZE);

        appender = builder.open();
    }

    @After
    public void cleanup() {
        IOUtils.closeQuietly(appender);
        Utils.tryDelete(testDirectory);
    }

    @Test
    public void roll() {
        appender.append("data");
        String firstSegment = appender.currentSegment();

        assertEquals(1, appender.levels.depth());

        appender.roll();

        String secondSegment = appender.currentSegment();
        assertNotEquals(firstSegment, secondSegment);
        assertEquals(2, appender.levels.numSegments());
        assertEquals(2, appender.levels.depth());
        assertEquals(firstSegment, appender.levels.get(1).name());
    }

    @Test
    public void roll_size_based() {
        StringBuilder sb = new StringBuilder();
        while (sb.length() <= SEGMENT_SIZE) {
            sb.append(UUID.randomUUID().toString());
        }
        appender.append(sb.toString());
        appender.append("new-segment");

        assertEquals(2, appender.levels.numSegments());
    }

    @Test
    public void scanner_returns_in_insertion_order_with_multiple_segments() {

        appender.append("a");
        appender.append("b");

        appender.roll();

        appender.append("c");
        appender.flush();

        assertEquals(2, appender.levels.numSegments());

        LogIterator<String> logIterator = appender.scanner();

        String lastValue = null;

        while (logIterator.hasNext()) {
            lastValue = logIterator.next();
        }

        assertEquals("c", lastValue);
    }

    @Test
    public void positionOnSegment() {

        int segmentIdx = 0;
        long positionOnSegment = 32;
        long position = appender.toSegmentedPosition(segmentIdx, positionOnSegment);

        int segment = appender.getSegment(position);
        long foundPositionOnSegment = appender.getPositionOnSegment(position);

        assertEquals(segmentIdx, segment);
        assertEquals(positionOnSegment, foundPositionOnSegment);
    }

    @Test
    public void get() {
        long pos1 = appender.append("1");
        long pos2 = appender.append("2");

        appender.flush();

        assertEquals("1", appender.get(pos1));
        assertEquals("2", appender.get(pos2));
    }

    @Test
    public void position() {

        assertEquals(0, appender.position());

        long pos1 = appender.append("1");
        long pos2 = appender.append("2");
        long pos3 = appender.append("3");

        appender.flush();
        LogIterator<String> logIterator = appender.scanner();

        assertEquals(pos1, logIterator.position());
        String found = logIterator.next();
        assertEquals("1", found);

        assertEquals(pos2, logIterator.position());
        found = logIterator.next();
        assertEquals("2", found);

        assertEquals(pos3, logIterator.position());
        found = logIterator.next();
        assertEquals("3", found);
    }

    @Test
    public void reader_position() {

        StringBuilder sb = new StringBuilder();
        while (sb.length() <= SEGMENT_SIZE) {
            sb.append(UUID.randomUUID().toString());
        }

        String lastEntry = "FIRST-ENTRY-NEXT-SEGMENT";
        long lastWrittenPosition = appender.append(lastEntry);

        appender.flush();

        LogIterator<String> logIterator = appender.scanner(lastWrittenPosition);

        assertTrue(logIterator.hasNext());
        assertEquals(lastEntry, logIterator.next());
    }

    @Test
    public void reopen() throws IOException {
        File testDirectory = Files.createTempDirectory(".fstoreTest2").toFile();
        try {
            testDirectory.deleteOnExit();
            if (testDirectory.exists()) {
                Utils.tryDelete(testDirectory);
            }

            long pos1;
            long pos2;
            long pos3;
            long pos4;

            try (SimpleLogAppender<String> testAppender = LogAppender.builder(testDirectory, Serializers.STRING).open()) {
                pos1 = testAppender.append("1");
                pos2 = testAppender.append("2");
                pos3 = testAppender.append("3");
            }
            try (SimpleLogAppender<String> testAppender = LogAppender.builder(testDirectory, Serializers.STRING).open()) {
                pos4 = testAppender.append("4");
            }
            try (SimpleLogAppender<String> testAppender = LogAppender.builder(testDirectory, Serializers.STRING).open()) {
                assertEquals("1", testAppender.get(pos1));
                assertEquals("2", testAppender.get(pos2));
                assertEquals("3", testAppender.get(pos3));
                assertEquals("4", testAppender.get(pos4));

                Set<String> values = testAppender.stream().collect(Collectors.toSet());
                assertTrue(values.contains("1"));
                assertTrue(values.contains("2"));
                assertTrue(values.contains("3"));
                assertTrue(values.contains("4"));
            }
        } finally {
            Utils.tryDelete(testDirectory);
        }
    }

    @Test
    public void entries() {
        appender.append("a");
        appender.append("b");

        assertEquals(2, appender.entries());

        appender.close();

        appender = LogAppender.builder(testDirectory, Serializers.STRING).open();
        assertEquals(2, appender.entries());
        assertEquals(2, appender.stream().count());
    }

    @Test
    public void when_reopened_use_metadata_instead_builder_params() {
        appender.append("a");
        appender.append("b");

        assertEquals(2, appender.entries());

        appender.close();

        appender = LogAppender.builder(testDirectory, Serializers.STRING).open();
        assertEquals(2, appender.entries());
        assertEquals(2, appender.stream().count());
    }

    @Test
    public void reopen_brokenEntry() throws IOException {
        File testDirectory = Files.createTempDirectory(".fstoreTest2").toFile();
        try {
            testDirectory.deleteOnExit();
            if (testDirectory.exists()) {
                Utils.tryDelete(testDirectory);
            }

            String segmentName;
            try (SimpleLogAppender<String> testAppender = LogAppender.builder(testDirectory, Serializers.STRING).open()) {
                testAppender.append("1");
                testAppender.append("2");
                testAppender.append("3");

                //get last segment (in this case there will be always one)
                segmentName = testAppender.segmentsNames().get(testAppender.segmentsNames().size() - 1);
            }

            //write broken data

            File file = new File(testDirectory, segmentName);
            try (Storage storage = new RafStorage(file, file.length(), Mode.READ_WRITE)) {
                ByteBuffer broken = ByteBuffer.allocate(Log.ENTRY_HEADER_SIZE + 4);
                broken.putInt(444); //expected length
                broken.putInt(123); // broken checksum
                broken.putChar('A'); // broken data
                storage.write(broken);
            }

            try (SimpleLogAppender<String> testAppender = LogAppender.builder(testDirectory, Serializers.STRING).open()) {
                testAppender.append("4");
            }

            try (SimpleLogAppender<String> testAppender = LogAppender.builder(testDirectory, Serializers.STRING).open()) {
                Set<String> values = testAppender.stream().collect(Collectors.toSet());
                assertTrue(values.contains("1"));
                assertTrue(values.contains("2"));
                assertTrue(values.contains("3"));
                assertTrue(values.contains("4"));
            }

        } finally {
            Utils.tryDelete(testDirectory);
        }
    }

    @Test
    public void segmentBitShift() {
        for (int i = 0; i < appender.maxSegments; i++) {
            long position = appender.toSegmentedPosition(i, 0);
            long foundSegment = appender.getSegment(position);
            assertEquals("Failed on segIdx " + i + " - position: " + position + " - foundSegment: " + foundSegment, i, foundSegment);
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void toSegmentedPosition_invalid() {
        long invalidAddress = appender.maxSegments + 1;
        appender.toSegmentedPosition(invalidAddress, 0);

    }

    @Test
    public void getPositionOnSegment() {

        long value = 1;
        long position = appender.getPositionOnSegment(1);
        assertEquals("Failed on position: " + position, value, position);

        value = appender.maxAddressPerSegment / 2;
        position = appender.getPositionOnSegment(value);
        assertEquals("Failed on position: " + position, value, position);

        value = appender.maxAddressPerSegment;
        position = appender.getPositionOnSegment(value);
        assertEquals("Failed on position: " + position, value, position);
    }

    @Test
    public void compact() {
        appender.append("SEGMENT-A");
        appender.roll();

        appender.append("SEGMENT-B");
        appender.roll();

        assertEquals(3, appender.levels.numSegments());
        assertEquals(2, appender.entries());

        appender.compact(1);

        assertEquals(2, appender.levels.numSegments());
        assertEquals(3, appender.levels.depth());
        assertEquals(2, appender.entries());

        List<String> found = appender.stream().collect(Collectors.toList());
        assertEquals("SEGMENT-A", found.get(0));
        assertEquals("SEGMENT-B", found.get(1));
    }

    @Test
    public void depth_is_correct_after_merge() {

        appender.append("SEGMENT-A");
        appender.roll();

        appender.append("SEGMENT-B");
        appender.roll();

        appender.compact(1);

        assertEquals(3, appender.depth());

    }
}