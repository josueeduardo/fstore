package io.joshworks.fstore.lsmtree.sstable;

import io.joshworks.fstore.core.Codec;
import io.joshworks.fstore.core.cache.Cache;
import io.joshworks.fstore.core.io.StorageMode;
import io.joshworks.fstore.core.io.buffers.ThreadLocalBufferPool;
import io.joshworks.fstore.core.util.FileUtils;
import io.joshworks.fstore.core.util.Memory;
import io.joshworks.fstore.core.util.Size;
import io.joshworks.fstore.log.segment.WriteMode;
import io.joshworks.fstore.serializer.Serializers;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.util.TreeSet;

import static io.joshworks.fstore.core.io.Storage.EOF;
import static io.joshworks.fstore.lsmtree.sstable.Entry.NO_MAX_AGE;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

public class SSTableTest {

    public static final int ITEMS = 200000;
    private SSTable<Integer, String> sstable;
    private File testFile;


    @Before
    public void setUp() {
        testFile = FileUtils.testFile();
        sstable = open(testFile);
    }

    private SSTable<Integer, String> open(File file) {
        return new SSTable<>(file,
                StorageMode.MMAP,
                Size.MB.of(100),
                Serializers.INTEGER,
                Serializers.VSTRING,
                new ThreadLocalBufferPool("pool", Size.MB.ofInt(1), false),
                WriteMode.LOG_HEAD,
                NO_MAX_AGE,
                Codec.noCompression(),
                Cache.noCache(),
                10000,
                0.01,
                Memory.PAGE_SIZE,
                1);
    }

    @After
    public void tearDown() {
        sstable.close();
        FileUtils.tryDelete(testFile);
    }

    @Test
    public void lower_step_1() {
        lowerWithStep(100000, 1);
    }

    @Test
    public void lower_step_2() {
        lowerWithStep(ITEMS, 2);
    }

    @Test
    public void lower_step_5() {
        lowerWithStep(ITEMS, 5);
    }

    @Test
    public void lower_step_7() {
        lowerWithStep(ITEMS, 7);
    }

    @Test
    public void higher_step_1() {
        higherWithStep(ITEMS, 1);
    }

    @Test
    public void higher_step_2() {
        higherWithStep(ITEMS, 2);
    }

    @Test
    public void higher_step_5() {
        higherWithStep(ITEMS, 5);
    }

    @Test
    public void higher_step_7() {
        higherWithStep(ITEMS, 7);
    }

    @Test
    public void floor_step_1() {
        floorWithStep(ITEMS, 1);
    }

    @Test
    public void floor_step_2() {
        floorWithStep(ITEMS, 2);
    }

    @Test
    public void floor_step_5() {
        floorWithStep(ITEMS, 5);
    }

    @Test
    public void floor_step_7() {
        floorWithStep(ITEMS, 7);
    }


    @Test
    public void ceiling_step_1() {
        ceilingWithStep(ITEMS, 1);
    }

    @Test
    public void ceiling_step_2() {
        ceilingWithStep(ITEMS, 2);
    }

    @Test
    public void ceiling_step_5() {
        ceilingWithStep(ITEMS, 5);
    }

    @Test
    public void ceiling_step_7() {
        ceilingWithStep(ITEMS, 7);
    }

    @Test
    public void floor_with_key_equals_last_entry_returns_last_entry() {
        addSomeEntries();

        //equals last
        Entry<Integer, String> found = sstable.floor(sstable.lastKey());
        assertNotNull(found);
        assertEquals(sstable.lastKey(), found.key);
    }

    @Test
    public void floor_with_key_greater_than_last_entry_returns_last_entry() {
        addSomeEntries();

        //greater than last
        Entry<Integer, String> found = sstable.floor(sstable.lastKey() + 1);
        assertNotNull(found);
        assertEquals(sstable.lastKey(), found.key);
    }

    @Test
    public void floor_with_key_less_than_first_entry_returns_null() {
        addSomeEntries();

        Entry<Integer, String> found = sstable.floor(sstable.firstKey() - 1);
        assertNull(found);
    }

    @Test
    public void floor_with_key_equals_first_entry_returns_first_entry() {
        addSomeEntries();

        Entry<Integer, String> found = sstable.floor(sstable.firstKey());
        assertNotNull(found);
        assertEquals(sstable.firstKey(), found.key);
    }

    @Test
    public void ceiling_with_key_less_than_first_entry_returns_first_entry() {
        addSomeEntries();

        Entry<Integer, String> found = sstable.ceiling(sstable.firstKey() - 1);
        assertNotNull(found);
        assertEquals(sstable.firstKey(), found.key);
    }

    @Test
    public void ceiling_with_key_equals_first_entry_returns_first_entry() {
        addSomeEntries();

        Entry<Integer, String> found = sstable.ceiling(sstable.firstKey());
        assertNotNull(found);
        assertEquals(sstable.firstKey(), found.key);
    }

    @Test
    public void ceiling_with_key_greater_than_last_entry_returns_null() {
        addSomeEntries();

        Entry<Integer, String> found = sstable.ceiling(sstable.lastKey() + 1);
        assertNull(found);
    }

    @Test
    public void ceiling_with_key_equals_last_entry_returns_last_entry() {
        addSomeEntries();

        Entry<Integer, String> found = sstable.ceiling(sstable.lastKey());
        assertNotNull(found);
        assertEquals(sstable.lastKey(), found.key);
    }

    @Test
    public void higher_with_key_greater_than_lastKey_returns_null() {
        addSomeEntries();

        Entry<Integer, String> found = sstable.higher(sstable.lastKey() + 1);
        assertNull(found);
    }

    @Test
    public void higher_with_key_equals_lastKey_returns_null() {
        addSomeEntries();

        Entry<Integer, String> found = sstable.higher(sstable.lastKey());
        assertNull(found);
    }

    @Test
    public void higher_with_key_less_than_firstKey_returns_firstEntry() {
        addSomeEntries();

        Entry<Integer, String> found = sstable.higher(sstable.firstKey() - 1);
        assertNotNull(found);
        assertEquals(sstable.firstKey(), found.key);
    }

    @Test
    public void higher_with_key_lowest_key_returns_firstEntry() {
        addSomeEntries();

        Entry<Integer, String> found = sstable.higher(Integer.MIN_VALUE);
        assertNotNull(found);
        assertEquals(sstable.firstKey(), found.key);
    }

    @Test
    public void lower_with_key_less_than_firstKey_returns_null() {
        addSomeEntries();

        Entry<Integer, String> found = sstable.lower(sstable.firstKey() - 1);
        assertNull(found);
    }

    @Test
    public void lower_with_key_equals_firstKey_returns_null() {
        addSomeEntries();

        Entry<Integer, String> found = sstable.lower(sstable.firstKey());
        assertNull(found);
    }

    @Test
    public void lower_with_key_greater_than_lastKey_returns_lastEntry() {
        addSomeEntries();

        Entry<Integer, String> found = sstable.lower(sstable.lastKey() + 1);
        assertNotNull(found);
        assertEquals(sstable.lastKey(), found.key);
    }

    @Test
    public void lower_with_key_highest_key_returns_lastEntry() {
        addSomeEntries();

        Entry<Integer, String> found = sstable.lower(Integer.MAX_VALUE);
        assertNotNull(found);
        assertEquals(sstable.lastKey(), found.key);
    }

    @Test
    public void floor_with_deleted_keys_return_correct_entry() {
        for (int i = 0; i < 10000; i++) {
            sstable.append(Entry.add(i, String.valueOf(i)));
        }

        //delete all
        for (int i = 0; i < 10000; i += 2) {
            sstable.append(Entry.add(i, String.valueOf(i)));
        }

        //equals last
        Entry<Integer, String> found = sstable.floor(sstable.lastKey());
        assertNotNull(found);
        assertEquals(sstable.lastKey(), found.key);
    }

    private void addSomeEntries() {
        for (int i = 0; i < 10; i++) {
            sstable.append(Entry.add(i, String.valueOf(i)));
        }
        sstable.roll(1, false);
    }

    private void floorWithStep(int items, int steps) {
        TreeSet<Integer> treeSet = new TreeSet<>();
        for (int i = 0; i < items; i += steps) {
            treeSet.add(i);
            sstable.append(Entry.add(i, String.valueOf(i)));
        }
        sstable.roll(1, false);

        for (int i = 0; i < items; i += 1) {
            if (i == 806707) {
                System.out.println();
            }
            Integer expected = treeSet.floor(i);
            Entry<Integer, String> floor = sstable.floor(i);
            assertNotNull("Failed on " + i, floor);
            assertEquals("Failed on " + i, expected, floor.key);
        }

        Entry<Integer, String> floor = sstable.floor(Integer.MAX_VALUE);
        Entry<Integer, String> last = sstable.last();
        assertEquals(last, floor);
    }

    private void ceilingWithStep(int items, int steps) {
        TreeSet<Integer> treeSet = new TreeSet<>();
        for (int i = 0; i < items; i += steps) {
            if (i == 806707) {
                System.out.println();
            }
            treeSet.add(i);
            long append = sstable.append(Entry.add(i, String.valueOf(i)));
            if (append == EOF) {
                System.out.println();
            }
        }
        sstable.roll(1, false);


        for (int i = 0; i < items - steps; i += 1) {
            if (i == 806707) {
                System.out.println();
            }
            Integer expected = treeSet.ceiling(i);
            Entry<Integer, String> ceiling = sstable.ceiling(i);
            assertNotNull("Failed on " + i, ceiling);
            assertEquals("Failed on " + i, expected, ceiling.key);
        }

        Entry<Integer, String> ceiling = sstable.ceiling(0);
        Entry<Integer, String> first = sstable.first();
        assertEquals(first, ceiling);

        ceiling = sstable.ceiling(Integer.MIN_VALUE);
        first = sstable.first();
        assertEquals(first, ceiling);
    }

    private void lowerWithStep(int items, int steps) {
        TreeSet<Integer> treeSet = new TreeSet<>();
        for (int i = 0; i < items; i += steps) {
            treeSet.add(i);
            sstable.append(Entry.add(i, String.valueOf(i)));
        }
        sstable.roll(1, false);

        for (int i = 1; i < items; i += 1) {
            Integer expected = treeSet.lower(i);
            Entry<Integer, String> lower = sstable.lower(i);
            assertNotNull("Failed on " + i, lower);
            assertEquals("Failed on " + i, expected, lower.key);
        }

        Entry<Integer, String> lower = sstable.lower(0);
        assertNull(lower);

        Entry<Integer, String> lowest = sstable.lower(Integer.MAX_VALUE);
        assertEquals(sstable.last(), lowest);
    }

    private void higherWithStep(int items, int steps) {
        TreeSet<Integer> treeSet = new TreeSet<>();
        for (int i = 0; i < items; i += steps) {
            treeSet.add(i);
            sstable.append(Entry.add(i, String.valueOf(i)));
        }
        sstable.roll(1, false);


        for (int i = 0; i < items - steps; i += 1) {
            Integer expected = treeSet.higher(i);
            Entry<Integer, String> higher = sstable.higher(i);
            assertNotNull("Failed on " + i, higher);
            assertEquals("Failed on " + i, expected, higher.key);
        }

        Entry<Integer, String> higher = sstable.higher(sstable.lastKey());
        assertNull(higher);

        Entry<Integer, String> highest = sstable.higher(Integer.MAX_VALUE);
        assertNull(highest);
    }
}