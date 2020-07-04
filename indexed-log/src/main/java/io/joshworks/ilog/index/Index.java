package io.joshworks.ilog.index;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

import static java.util.Objects.requireNonNull;

/**
 * A NON-CLUSTERED, UNIQUE, ORDERED index that uses binary search to read elements
 * - Append only
 * - Entries must be of a fixed size
 * - Insertion must be ORDERED
 * - Entries must be unique
 * <p>
 * If opening from an existing file, the index is marked as read only.
 * <p>
 * FORMAT:
 * KEY (N bytes)
 * LOG_POS (8 bytes)
 */
public class Index implements Closeable {

    private final MappedFile mf;
    private final RowKey comparator;
    private final AtomicBoolean readOnly = new AtomicBoolean();
    public static final int NONE = -1;

    public static int MAX_SIZE = Integer.MAX_VALUE - 8;

    public Index(File file, int size, RowKey comparator) {
        this.comparator = comparator;
        try {
            boolean newFile = file.createNewFile();
            if (newFile) {
                int alignedSize = align(size);
                this.mf = MappedFile.create(file, alignedSize);
            } else { //existing file
                this.mf = MappedFile.open(file);
                long fileSize = mf.capacity();
                if (fileSize % entrySize() != 0) {
                    throw new IllegalStateException("Invalid index file length: " + fileSize);
                }
                readOnly.set(true);
            }

        } catch (IOException ioex) {
            throw new RuntimeException("Failed to create index", ioex);
        }
    }

    public void write(ByteBuffer src, int recordSize, long position) {
        write(src, src.position(), src.limit(), recordSize, position);
    }

    /**
     * Writes an entry to this index
     *
     * @param src      The source buffer to get the key from
     * @param kOffset  The key offset in the source buffer
     * @param kCount   The size of the key, must match Rowkey#keySize()
     * @param position The entry position in the log
     */
    public void write(ByteBuffer src, int kOffset, int kCount, int recordSize, long position) {
        if (isFull()) {
            throw new IllegalStateException("Index is full");
        }
        if (kCount != comparator.keySize()) {
            throw new RuntimeException("Invalid index key length, expected " + comparator.keySize() + ", got " + kCount);
        }
        mf.put(src, kOffset, kCount);
        mf.putLong(position);
        mf.putInt(recordSize);
    }

    public int find(ByteBuffer key, IndexFunction func) {
        requireNonNull(key, "Key must be provided");
        int remaining = key.remaining();
        int kSize = keySize();
        if (remaining != kSize) {
            throw new IllegalArgumentException("Invalid key size: " + remaining + ", expected: " + kSize);
        }
        if (entries() == 0) {
            return NONE;
        }
        int idx = binarySearch(key);
        return func.apply(idx);
    }


    private int binarySearch(ByteBuffer key) {
        return binarySearch(key, mf.buffer(), 0, size(), entrySize(), comparator);
    }

    private static int binarySearch(ByteBuffer key, ByteBuffer data, int dataStart, int dataCount, int entrySize, RowKey comparator) {
        if (dataCount % entrySize != 0) {
            throw new IllegalArgumentException("Read buffer must be multiple of " + entrySize);
        }
        int entries = dataCount / entrySize;

        int low = 0;
        int high = entries - 1;

        while (low <= high) {
            int mid = (low + high) >>> 1;
            int readPos = dataStart + (mid * entrySize);
            if (readPos < dataStart || readPos > dataStart + dataCount) {
                throw new IndexOutOfBoundsException("Index out of bounds: " + readPos);
            }
            int cmp = comparator.compare(data, readPos, key, key.position());
            if (cmp < 0)
                low = mid + 1;
            else if (cmp > 0)
                high = mid - 1;
            else
                return mid;
        }
        return -(low + 1);
    }

    public long readPosition(int idx) {
        if (idx < 0 || idx >= entries()) {
            return NONE;
        }
        int startPos = idx * entrySize();
        int positionOffset = startPos + comparator.keySize();
        return mf.getLong(positionOffset);
    }

    public int readEntrySize(int idx) {
        if (idx < 0 || idx >= entries()) {
            return NONE;
        }
        int startPos = idx * entrySize();
        int positionOffset = startPos + comparator.keySize() + Long.BYTES;
        return mf.getInt(positionOffset);
    }

    public boolean isFull() {
        return mf.position() >= mf.capacity();
    }

    public int entries() {
        //there can be a partial write in the buffer, doing this makes sure it won't be considered
        int entrySize = entrySize();
        return (mf.position() / entrySize);
    }

    public void delete() throws IOException {
        mf.delete();
    }

    public void truncate() {
        mf.truncate(mf.position());
    }

    /**
     * Complete this index and mark it as read only.
     */
    public void complete() {
        truncate();
        readOnly.set(true);
    }

    public void flush() {
        mf.flush();
    }

    @Override
    public void close() {
        mf.close();
    }

    protected int entrySize() {
        return comparator.keySize() + Long.BYTES + Integer.BYTES;
    }

    public int keySize() {
        return comparator.keySize();
    }

    private int align(int size) {
        int entrySize = entrySize();
        int aligned = entrySize * (size / entrySize);
        if (aligned <= 0) {
            throw new IllegalArgumentException("Invalid index size: " + size);
        }
        return aligned;
    }

    public String name() {
        return mf.name();
    }

    public int size() {
        return entries() * entrySize();
    }

    public void first(ByteBuffer dst) {
        if (entries() == 0) {
            return;
        }
        if (dst.remaining() != keySize()) {
            throw new RuntimeException("Buffer key length mismatch");
        }
        mf.get(dst, 0, keySize());
    }

    public int remaining() {
        return mf.capacity() / entrySize();
    }
}