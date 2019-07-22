package io.joshworks.fstore.index.filter;

import java.nio.ByteBuffer;
import java.util.BitSet;
import java.util.Objects;

/**
 * Format
 * Length -> 4bytes
 * Number of bits (m) -> 4bytes
 * Number of hash (k) -> 4bytes
 * Data -> long[]
 */
public class BloomFilter {

    private static final int HEADER_SIZE = Integer.BYTES * 3;

    BitSet hashes;
    private BloomFilterHasher hash;
    private final int m; // The number of bits in the filter
    private int k; // Number of hash functions

    /**
     * @param n The expected number of elements in the filter
     * @param p The acceptable false positive rate
     */
    private BloomFilter(long n, double p) {
        this.m = getNumberOfBits(p, n);
        this.k = getOptimalNumberOfHashesByBits(n, this.m);
        this.hash = BloomFilterHasher.murmur64();
        this.hashes = new BitSet(this.m);
    }

    /**
     * Used to load from file only
     *
     * @param hashes The table containing the data
     * @param m      The number of bits in the 'hashes'
     * @param k      The number of hash functions
     */
    private BloomFilter(BitSet hashes, int m, int k) {
        this.hashes = hashes;
        this.hash = BloomFilterHasher.murmur64();
        this.m = m;
        this.k = k;
    }

    /**
     * Add an element to the container
     */
    public void add(ByteBuffer key) {
        for (int h : hash.hash(hashes.size(), k, key)) {
            hashes.set(h);
        }
    }

    /**
     * Returns true if the element is in the container.
     * Returns false with a probability ≈ 1-e^(-ln(2)² * m/n)
     * if the element is not in the container.
     **/
    public boolean contains(ByteBuffer key) {
        for (int h : hash.hash(hashes.size(), k, key))
            if (!hashes.get(h))
                return false;
        return true;
    }

    /**
     * Removes all of the elements from this filter.
     **/
    public void clear() {
        hashes.clear();
    }

    /**
     * Generate a unique hash representing the filter
     **/
    @Override
    public int hashCode() {
        return hashes.hashCode() ^ k;
    }

    /**
     * Merge another bloom filter into the current one.
     * After this operation, the current bloom filter contains all elements in
     * other.
     **/
    public void merge(BloomFilter other) {
        if (other.k != this.k || other.hashes.size() != this.hashes.size()) {
            throw new IllegalArgumentException("Incompatible bloom filters");
        }
        this.hashes.or(other.hashes);
    }

    /**
     * k = (m / n) ln 2 from wikipedia.
     *
     * @param n the number of elements expected.
     * @param m the number of bytes allowed.
     * @return the best number of hashes.
     */
    private int getOptimalNumberOfHashesByBits(long n, long m) {
        return (int) Math.ceil(Math.log(2) * ((double) m / n));
    }


    /**
     * Calculate the number of bits needed to produce the provided probability of false
     * positives with the given element position.
     *
     * @param p The probability of false positives.
     * @param n The estimated number of elements.
     * @return The number of bits.
     */
    private static int getNumberOfBits(double p, long n) {
        return (int) (Math.abs(n * Math.log(p)) / (Math.pow(Math.log(2), 2)));
    }


    public void writeTo(ByteBuffer dst) {
        long[] items = hashes.toLongArray();
        int dataLength = items.length * Long.BYTES;
        int totalSize = dataLength + HEADER_SIZE;


        //Format
        //Length -> 4bytes
        //Number of bits (m) -> 4bytes
        //Number of hash (k) -> 4bytes
        //Data -> long[]
        dst.putInt(dataLength);
        dst.putInt(this.m);
        dst.putInt(this.k);
        for (long item : items) {
            dst.putLong(item);
        }
    }

    public static BloomFilter create(long n, double p) {
        return new BloomFilter(n, p);
    }

    public static BloomFilter load(ByteBuffer data) {

        int length = data.getInt(); //unused
        int m = data.getInt();
        int k = data.getInt();

        long[] longs = new long[data.remaining() / Long.BYTES];
        int i = 0;
        while (data.hasRemaining()) {
            longs[i++] = data.getLong();
        }

        BitSet bitSet = new BitSet(m);
        bitSet.or(BitSet.valueOf(longs));

        return new BloomFilter(bitSet, m, k);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        BloomFilter that = (BloomFilter) o;
        return Objects.equals(hashes, that.hashes);
    }
}