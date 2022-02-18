package de.geolykt.presence.test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import de.geolykt.presence.common.util.RegionatedIntIntToObjectMap;

public class IntIntToObjectMapTests {

    private static record IntIntEntry(int key1, int key2) {}

    /**
     * Checks whether a simple single-threaded insertion results in expected behaviour.
     * Elements are obtained via a parallel stream.
     */
    @Test
    public void testSimpleInsertion() {
        RegionatedIntIntToObjectMap<Integer> map = new RegionatedIntIntToObjectMap<>();
        Map<IntIntEntry, Integer> expected = new HashMap<>();
        for (long i = -100_000; i < 100_000; i++) {
            int value = (int) (i ^ ~(i >>> 32));
            int key1 = (int) i >>> 32;
            int key2 = (int) i & 0x0000_FFFF;
            map.put(key1, key2, value);
            expected.put(new IntIntEntry(key1, key2), value);
        }
        expected.entrySet().stream().forEach(entry -> {
            Integer actual = map.get(entry.getKey().key1, entry.getKey().key2);
            assertEquals(entry.getValue(), actual);
        });
    }

    @Test
    public void testConcurrentInsertion() {
        RegionatedIntIntToObjectMap<Integer> map = new RegionatedIntIntToObjectMap<>();
        Map<IntIntEntry, Integer> expected = new ConcurrentHashMap<>();
        AtomicInteger completedTasks = new AtomicInteger(0);
        for (int i = 0; i < 10; i++) {
            ForkJoinPool.commonPool().execute(() -> {
                ThreadLocalRandom rand = ThreadLocalRandom.current();
                for (int x = 0; x < 1_000_000; x++) {
                    int int1 = rand.nextInt(100);
                    int int2 = rand.nextInt(100);
                    int randValue = rand.nextInt();
                    Integer value = map.get(int1, int2);
                    loop10001: {
                        if (value == null) {
                            value = map.putIfAbsent(int1, int2, randValue);
                            if (value == null) {
                                break loop10001;
                            }
                        }
                        while (randValue > value.intValue()) {
                            if (map.compareAndSet(int1, int2, value, randValue)) {
                                break;
                            }
                            value = map.get(int1, int2);
                        }
                    }
                    IntIntEntry entry = new IntIntEntry(int1, int2);
                    value = expected.get(entry);
                    if (value == null) {
                        value = expected.putIfAbsent(entry, randValue);
                        if (value == null) {
                            continue;
                        }
                    }
                    while (randValue > value) {
                        if (expected.replace(entry, value, randValue)) {
                            break;
                        }
                        value = expected.get(entry);
                    }
                }
                completedTasks.incrementAndGet();
            });
        }
        while (completedTasks.get() != 10) {
            assertDoesNotThrow(() -> {
                Thread.sleep(50);
            });
        }
        expected.entrySet().parallelStream().forEach(fullEntry -> {
            IntIntEntry key = fullEntry.getKey();
            Integer expectedValue = fullEntry.getValue();
            assertEquals(expectedValue, map.get(key.key1, key.key2));
        });
    }
}
