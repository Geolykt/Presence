package de.geolykt.presence.common.util;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReferenceArray;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * A (int, int) -> Object map-like structure which works by sub-dividing the two integer parameters
 * into two-dimensional "regions". This means that the data structure is most efficient if the integer
 * keys are near 0 and compact. However the data structure allows for negatively-sized without further impact
 * by implementing a supercell system and by applying a bit mask to negative values to make it more inline
 * with the 0-centric indexing. Each supercell can be resized independently so if the
 * supercells are to expand asymmetrically it would consume considerably less memory if all cells were resized.
 * The same cannot be said about the regions within the supercells. While as long as the supercells are uninitalized
 * they are nonexistent, as soon as they (the array or regions) need to be resized they will grow in a square fashion.
 * It is fully incapable of doing rectangular resizing and if resized it will resize by a power of two,
 * where as by default it is capable of storing 16 elements. Regions are (as soon as used) initialized to their full size,
 * which would be 65536 elements.
 *
 * <p> The key features of this map are the follows:
 * <ul>
 *   <li>Guaranteed constant runtime complexity for get and put operations</li>
 *   <li>No memory impact caused by (auto-)boxing integers</li>
 *   <li>Mostly Concurrent (see later paragraphs on the limitations)</li>
 * </ul>
 *
 * <p> The key disadvantages of this map are:
 * <ul>
 *   <li>No iteration over keys nor values</li>
 *   <li>This map does not extend the map interface</li>
 *   <li>High idle memory footprint</li>
 *   <li>Reduced throughput compared to structures such {@link ConcurrentHashMap}</li>
 * </ul>
 *
 * <p> By saying it has a high idle memory footprint I mean it. An empty {@link RegionatedIntIntToObjectMap} will
 * have an array with the size of 65k allocated which holds the supercells of the map. These supercells
 * will all be constructed, though will be uninitialised until used. A supercell with a single value stored will
 * create an array with the size of 65k elements, which also is pretty hefty.
 *
 * <p> As said before, this class is mostly concurrent. More specifically, while this class should be fully thread-safe,
 * some operations may results in locks being used. Each supercell uses itself as a lock in case it's internal list
 * of regions needs to be created or expanded. This means that {@link #get(int, int)} and {@link #compareAndSet(int, int, Object, Object)}
 * can block for a bit longer than usual if the value is previously unmapped and there isn't a value in the current
 * region set. Additionally the {@link #equals(Object)} method is blocking write requests to the regions.
 * Usage fo that method is not recommended. Similarly {@link #hashCode()} does only yield an identity hashcode.
 *
 * JMH Benchmarks for the #set operation:
 * <pre>
 * Code (for MyBenchmark.concurrentHashMap, others are very similar):
 * {@code
    @Benchmark
    @BenchmarkMode(Mode.All)
    @Threads(Threads.MAX)
    @OperationsPerInvocation(256 * 256)
    public void concurrentHashMap() {
        Object o = new Object();
        ConcurrentHashMap<Long, Object> map = new ConcurrentHashMap<>();

        for (int x = 0; x < 0xFF; x++) {
            for (int y = 0; y < 0xFF; y++) {
                map.put((((long) x) << 32) | y & (~0L & Integer.MAX_VALUE), o);
            }
        }
    }}
 *
 * Benchmark                                                                      Mode     Cnt          Score         Error  Units
 * MyBenchmark.concurrentHashMap                                                 thrpt      25   10638525.246 ±   72978.176  ops/s
 * MyBenchmark.hashMap                                                           thrpt      25   12161844.951 ±  101108.904  ops/s
 * MyBenchmark.regionatedIntIntToObjectMap                                       thrpt      25  226600144.895 ± 5339520.216  ops/s
 * MyBenchmark.concurrentHashMap                                                  avgt      25         ≈ 10⁻⁶                 s/op
 * MyBenchmark.hashMap                                                            avgt      25         ≈ 10⁻⁶                 s/op
 * MyBenchmark.regionatedIntIntToObjectMap                                        avgt      25         ≈ 10⁻⁸                 s/op
 * MyBenchmark.concurrentHashMap                                                sample   37542         ≈ 10⁻⁶                 s/op
 * MyBenchmark.concurrentHashMap:concurrentHashMap·p0.00                        sample                 ≈ 10⁻⁷                 s/op
 * MyBenchmark.concurrentHashMap:concurrentHashMap·p0.50                        sample                 ≈ 10⁻⁶                 s/op
 * MyBenchmark.concurrentHashMap:concurrentHashMap·p0.90                        sample                 ≈ 10⁻⁶                 s/op
 * MyBenchmark.concurrentHashMap:concurrentHashMap·p0.95                        sample                 ≈ 10⁻⁶                 s/op
 * MyBenchmark.concurrentHashMap:concurrentHashMap·p0.99                        sample                 ≈ 10⁻⁶                 s/op
 * MyBenchmark.concurrentHashMap:concurrentHashMap·p0.999                       sample                 ≈ 10⁻⁶                 s/op
 * MyBenchmark.concurrentHashMap:concurrentHashMap·p0.9999                      sample                 ≈ 10⁻⁶                 s/op
 * MyBenchmark.concurrentHashMap:concurrentHashMap·p1.00                        sample                 ≈ 10⁻⁶                 s/op
 * MyBenchmark.hashMap                                                          sample   41173         ≈ 10⁻⁶                 s/op
 * MyBenchmark.hashMap:hashMap·p0.00                                            sample                 ≈ 10⁻⁷                 s/op
 * MyBenchmark.hashMap:hashMap·p0.50                                            sample                 ≈ 10⁻⁶                 s/op
 * MyBenchmark.hashMap:hashMap·p0.90                                            sample                 ≈ 10⁻⁶                 s/op
 * MyBenchmark.hashMap:hashMap·p0.95                                            sample                 ≈ 10⁻⁶                 s/op
 * MyBenchmark.hashMap:hashMap·p0.99                                            sample                 ≈ 10⁻⁶                 s/op
 * MyBenchmark.hashMap:hashMap·p0.999                                           sample                 ≈ 10⁻⁶                 s/op
 * MyBenchmark.hashMap:hashMap·p0.9999                                          sample                 ≈ 10⁻⁶                 s/op
 * MyBenchmark.hashMap:hashMap·p1.00                                            sample                 ≈ 10⁻⁶                 s/op
 * MyBenchmark.regionatedIntIntToObjectMap                                      sample  746352         ≈ 10⁻⁸                 s/op
 * MyBenchmark.regionatedIntIntToObjectMap:regionatedIntIntToObjectMap·p0.00    sample                 ≈ 10⁻⁸                 s/op
 * MyBenchmark.regionatedIntIntToObjectMap:regionatedIntIntToObjectMap·p0.50    sample                 ≈ 10⁻⁸                 s/op
 * MyBenchmark.regionatedIntIntToObjectMap:regionatedIntIntToObjectMap·p0.90    sample                 ≈ 10⁻⁸                 s/op
 * MyBenchmark.regionatedIntIntToObjectMap:regionatedIntIntToObjectMap·p0.95    sample                 ≈ 10⁻⁷                 s/op
 * MyBenchmark.regionatedIntIntToObjectMap:regionatedIntIntToObjectMap·p0.99    sample                 ≈ 10⁻⁷                 s/op
 * MyBenchmark.regionatedIntIntToObjectMap:regionatedIntIntToObjectMap·p0.999   sample                 ≈ 10⁻⁷                 s/op
 * MyBenchmark.regionatedIntIntToObjectMap:regionatedIntIntToObjectMap·p0.9999  sample                 ≈ 10⁻⁶                 s/op
 * MyBenchmark.regionatedIntIntToObjectMap:regionatedIntIntToObjectMap·p1.00    sample                 ≈ 10⁻⁶                 s/op
 * MyBenchmark.concurrentHashMap                                                    ss       5          0.191 ±       0.143   s/op
 * MyBenchmark.hashMap                                                              ss       5          0.138 ±       0.224   s/op
 * MyBenchmark.regionatedIntIntToObjectMap                                          ss       5          0.032 ±       0.029   s/op
 *</pre>
 * @param <V> The type of the value the map holds.
 * @author Geolykt
 * @implNote Due to how the class is written right now, if the developer knows that one key is usually smaller
 * than the other, then the generally lesser key should be the first argument.
 */
public class RegionatedIntIntToObjectMap<V extends Object> {

    /**
     * A supercell, the largest component of the map.
     * As of writing the javadocs the 8 most significant bits of the two keys are used to obtain the supercell
     * the value resides in. The actual current value is dictated by {@link RegionatedIntIntToObjectMap#SUPERCELL_SHIFT}.
     * This means that with 16 bits total, there are a total of around 65k Supercells in a single
     * regionated int int to object map.
     *
     * @author Geolykt
     */
    private static class Supercell<V> {
        // AtomicReferenceArray sadly does not support expansion at adequate terms
        private volatile AtomicReferenceArray<V>[] regions;
        // Used to reduce the performance required to call #equals()
        private volatile boolean modified = false;

        @Override
        public boolean equals(Object obj) {
            if (obj instanceof Supercell<?> other) {
                if (this.modified != other.modified) {
                    return false;
                }
                if (!this.modified && !other.modified) {
                    return true;
                }
                AtomicReferenceArray<?>[] otherRefArray = other.regions;
                AtomicReferenceArray<?>[] thisRefArray = this.regions;
                if (otherRefArray.length != thisRefArray.length) {
                    return false;
                }
                for (int i = 0; i < thisRefArray.length; i++) {
                    AtomicReferenceArray<?> a = otherRefArray[i];
                    AtomicReferenceArray<?> b = thisRefArray[i];
                    if (a == null) {
                        if (b == null) {
                            continue;
                        }
                        return false;
                    } else if (b == null) {
                        return false;
                    }
                    for (int j = 0; j < REGION_SIZE; j++) {
                        if (!Objects.equals(a.get(j), b.get(j))) {
                            return false;
                        }
                    }
                }
                return true;
            }
            return false;
        }

        /**
         * Obtains the currently set value and compares it with the expected value.
         * If they are equal (as per {@code ==}) the currently set value is
         * replaced and the method returns true, if they are not equal the currently stored value
         * is left unmodified and the method returns false.
         * The method behaves in an atomic manner.
         * 
         * @param key1 The first integer key
         * @param key2 The second integer key
         * @param expected The expected value. If this value is null the method acts similar to a {@link Map#putIfAbsent(Object, Object)}, as this map does not allow null values.
         * @param value The value to be set if the current value is the expected value.
         * @return True if the cell was modified, false otherwise
         */
        public boolean compareAndSet(int key1, int key2, @Nullable V expected, @NotNull V value) {
            this.modified = true;
            int position = (key1 & REGION_BITMASK) | ((key2 & REGION_BITMASK) >> CELL_BIT_SHIFT);

            if (regions == null || regions.length <= position) {
                synchronized (this) {
                    if (regions == null) {
                        int length = 16;
                        while (length <= position) {
                            length = length << 1;
                        }
                        @SuppressWarnings("unchecked") // Why, java, why??? (I really hope valhalla remedies this issue)
                        AtomicReferenceArray<V>[] var10001 = new AtomicReferenceArray[length];
                        this.regions = var10001;
                    } else if (regions.length <= position) {
                        AtomicReferenceArray<V>[] oldRegions = this.regions;
                        int newLength = oldRegions.length << 1;
                        while (newLength <= position) {
                            newLength = newLength << 1;
                        }
                        @SuppressWarnings("unchecked")
                        AtomicReferenceArray<V>[] newRegions = this.regions = new AtomicReferenceArray[newLength];
                        System.arraycopy(oldRegions, 0, newRegions, 0, oldRegions.length);
                    }
                }
            }
            AtomicReferenceArray<V> region = regions[position];
            if (region == null) {
                synchronized (this) {
                    if (regions[position] == null) {
                        regions[position] = new AtomicReferenceArray<>(REGION_SIZE);
                    }
                    region = regions[position];
                }
            }
            @SuppressWarnings("null")
            boolean ret = region.compareAndSet((key1 & CELL_BITMASK) << CELL_BIT_SHIFT | (key2 & CELL_BITMASK), expected, value);
            return ret;
        }

        @Nullable
        public V put(int key1, int key2, @NotNull V value) {
            this.modified = true;
            int position = (key1 & REGION_BITMASK) | ((key2 & REGION_BITMASK) >> CELL_BIT_SHIFT);

            if (regions == null || regions.length <= position) {
                synchronized (this) {
                    if (regions == null) {
                        int length = 16;
                        while (length <= position) {
                            length = length << 1;
                        }
                        @SuppressWarnings("unchecked") // Why, java, why??? (I really hope valhalla remedies this issue)
                        AtomicReferenceArray<V>[] var10001 = new AtomicReferenceArray[length];
                        this.regions = var10001;
                    } else if (regions.length <= position) {
                        AtomicReferenceArray<V>[] oldRegions = this.regions;
                        int newLength = oldRegions.length << 1;
                        while (newLength <= position) {
                            newLength = newLength << 1;
                        }
                        @SuppressWarnings("unchecked")
                        AtomicReferenceArray<V>[] newRegions = this.regions = new AtomicReferenceArray[newLength];
                        System.arraycopy(oldRegions, 0, newRegions, 0, oldRegions.length);
                    }
                }
            }
            AtomicReferenceArray<V> region = regions[position];
            if (region == null) {
                synchronized (this) {
                    if (regions[position] == null) {
                        regions[position] = new AtomicReferenceArray<>(REGION_SIZE);
                    }
                    region = regions[position];
                }
            }

            return region.getAndSet((key1 & CELL_BITMASK) << CELL_BIT_SHIFT | (key2 & CELL_BITMASK), value);
        }

        @Nullable
        public V get(int key1, int key2) {
            int position = (key1 & REGION_BITMASK) | ((key2 & REGION_BITMASK) >> CELL_BIT_SHIFT);

            if (regions == null || regions.length <= position) {
                return null;
            }
            AtomicReferenceArray<V> region = regions[position];
            if (region == null) {
                return null;
            }
            return region.get((key1 & CELL_BITMASK) << CELL_BIT_SHIFT | (key2 & CELL_BITMASK));
        }

        @Override
        public int hashCode() {
            // TODO Auto-generated method stub
            return super.hashCode();
        }

        /**
         * Obtains the currently set value and checks whether it exists.
         * If it exists, it returns the value, otherwise it sets the value of the cell to the specified value.
         * The method behaves in an atomic manner.
         * 
         * @param key1 The first integer key
         * @param key2 The second integer key
         * @param value The value to be set if the current value is the expected value.
         * @return The old value, if it does not exist, null.
         */
        @Nullable
        public V putIfAbsent(int key1, int key2, @NotNull V value) {
            this.modified = true;
            int position = (key1 & REGION_BITMASK) | ((key2 & REGION_BITMASK) >> CELL_BIT_SHIFT);

            if (regions == null || regions.length <= position) {
                synchronized (this) {
                    if (regions == null) {
                        int length = 16;
                        while (length <= position) {
                            length = length << 1;
                        }
                        @SuppressWarnings("unchecked") // Why, java, why??? (I really hope valhalla remedies this issue)
                        AtomicReferenceArray<V>[] var10001 = new AtomicReferenceArray[length];
                        this.regions = var10001;
                    } else if (regions.length <= position) {
                        AtomicReferenceArray<V>[] oldRegions = this.regions;
                        int newLength = oldRegions.length << 1;
                        while (newLength <= position) {
                            newLength = newLength << 1;
                        }
                        @SuppressWarnings("unchecked")
                        AtomicReferenceArray<V>[] newRegions = this.regions = new AtomicReferenceArray[newLength];
                        System.arraycopy(oldRegions, 0, newRegions, 0, oldRegions.length);
                    }
                }
            }
            AtomicReferenceArray<V> region = regions[position];
            if (region == null) {
                synchronized (this) {
                    if (regions[position] == null) {
                        regions[position] = new AtomicReferenceArray<>(REGION_SIZE);
                    }
                    region = regions[position];
                }
            }

            @SuppressWarnings("null") // God damn it my IDE
            V ret = region.compareAndExchange((key1 & CELL_BITMASK) << CELL_BIT_SHIFT | (key2 & CELL_BITMASK), null, value);
            return ret;
        }
    }

    private static final int CELL_BIT_SHIFT = 8; // Find the correct balance between empty size and region size. 8 yields more sane numbers, but 9 would result in much less supercells. 10 would explode the size of regions
    private static final int MSB_BITMASK = ~0 << CELL_BIT_SHIFT;
    private static final int CELL_BITMASK = ~0 ^ MSB_BITMASK;
    private static final int REGION_SIZE = (CELL_BITMASK << CELL_BIT_SHIFT) + CELL_BITMASK + 1; // Considering 32 bytes per object (which will not happen), it is 2 Mib in size, which is decently small
    private static final int SUPERCELL_SHIFT = Integer.SIZE / 2 - CELL_BIT_SHIFT;
    private static final int KEY_TO_SUPERCELL_SHIFT = Integer.SIZE - SUPERCELL_SHIFT;
    private static final int SUPERCELL_BIT_MASK = ~0 << (Integer.SIZE - SUPERCELL_SHIFT);
    private static final int REGION_BITMASK = MSB_BITMASK & ~SUPERCELL_BIT_MASK;

    @SuppressWarnings("unchecked")
    private final Supercell<V>[] supercells = new Supercell[1 << (SUPERCELL_SHIFT << 1)];

    public RegionatedIntIntToObjectMap() {
        for (int i = 0; i < supercells.length; i++) {
            supercells[i] = new Supercell<>();
        }
    }

    @Override
    public boolean equals(Object obj) {
        if (obj instanceof RegionatedIntIntToObjectMap<?> other) {
            for (int i = 0; i < supercells.length; i++) {
                if (!this.supercells[i].equals(other.supercells[i])) {
                    return false;
                }
            }
            return true;
        }
        return false;
    }

    @Nullable
    public V put(int key1, int key2, @NotNull V value) {
        if (key1 < 0) {
            key1 = key1 ^ ~SUPERCELL_BIT_MASK;
        }
        if (key2 < 0) {
            key2 = key2 ^ ~SUPERCELL_BIT_MASK;
        }
        int supercell = (key1 >>> KEY_TO_SUPERCELL_SHIFT) << SUPERCELL_SHIFT | key2 >>> KEY_TO_SUPERCELL_SHIFT;
        return supercells[supercell].put(key1, key2, value);
    }

    @Nullable
    public V get(int key1, int key2) {
        if (key1 < 0) {
            key1 = key1 ^ ~SUPERCELL_BIT_MASK;
        }
        if (key2 < 0) {
            key2 = key2 ^ ~SUPERCELL_BIT_MASK;
        }
        int supercell = (key1 >>> KEY_TO_SUPERCELL_SHIFT) << SUPERCELL_SHIFT | key2 >>> KEY_TO_SUPERCELL_SHIFT;
        return supercells[supercell].get(key1, key2);
    }

    @Override
    public int hashCode() {
        // TODO Auto-generated method stub
        return super.hashCode();
    }

    /**
     * Obtains the currently set value and compares it with the expected value.
     * If they are equal (as per {@link Objects#equals(Object, Object)}) the currently set value is
     * replaced and the method returns true, if they are not equal the currently stored value
     * is left unmodified and the method returns false.
     * The method behaves in an atomic manner.
     * 
     * @param key1 The first integer key
     * @param key2 The second integer key
     * @param expected The expected value. If this value is null the method acts similar to a {@link Map#putIfAbsent(Object, Object)}, as this map does not allow null values.
     * @param value The value to be set if the current value is the expected value.
     * @return True if the cell was modified, false otherwise
     */
    public boolean compareAndSet(int key1, int key2, @Nullable V expected, @NotNull V value) {
        if (key1 < 0) {
            key1 = key1 ^ ~SUPERCELL_BIT_MASK;
        }
        if (key2 < 0) {
            key2 = key2 ^ ~SUPERCELL_BIT_MASK;
        }
        int supercell = (key1 >>> KEY_TO_SUPERCELL_SHIFT) << SUPERCELL_SHIFT | key2 >>> KEY_TO_SUPERCELL_SHIFT;
        return supercells[supercell].compareAndSet(key1, key2, expected, value);
    }

    /**
     * Obtains the currently set value and checks whether it exists.
     * If it exists, it returns the value, otherwise it sets the value of the cell to the specified value.
     * The method behaves in an atomic manner.
     * 
     * @param key1 The first integer key
     * @param key2 The second integer key
     * @param value The value to be set if the current value is the expected value.
     * @return The old value, if it does not exist, null.
     */
    @Nullable
    public V putIfAbsent(int key1, int key2, @NotNull V value) {
        if (key1 < 0) {
            key1 = key1 ^ ~SUPERCELL_BIT_MASK;
        }
        if (key2 < 0) {
            key2 = key2 ^ ~SUPERCELL_BIT_MASK;
        }
        int supercell = (key1 >>> KEY_TO_SUPERCELL_SHIFT) << SUPERCELL_SHIFT | key2 >>> KEY_TO_SUPERCELL_SHIFT;
        return supercells[supercell].putIfAbsent(key1, key2, value);
    }
}
