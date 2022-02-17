package de.geolykt.presence.common.util;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * A concurrent map that accepts a UUID, Int and Int as a key.
 * This map was built to have next to no memory footprint which would usually
 * be happening when a temporary combined object is used as the key of a conventional
 * {@link ConcurrentHashMap}.
 * However it may perform slightly slower than the traditional approach mentioned above.
 * Additionally it internally uses a {@link ConcurrentHashMap} to map each UUID to a
 * {@link RegionatedIntIntToObjectMap}. <b> This means that the amount of unique UUIDs should be
 * low (a dozen at most)</b>! Furthermore the integer variables should be closer to 0/0.
 *
 * @author Geolykt
 *
 * @param <V> The type of the value of the map.
 */
public class UUIDIntIntConcurrentMap<V> {

    @NotNull
    private final ConcurrentHashMap<UUID, RegionatedIntIntToObjectMap<V>> root = new ConcurrentHashMap<>();

    @Nullable
    public V get(@NotNull UUID id, int int1, int int2) {
        RegionatedIntIntToObjectMap<V> map = root.get(id);
        if (map == null) {
            return null;
        }
        return map.get(int1, int2);
    }

    @Nullable
    public V set(@NotNull UUID id, int int1, int int2, @NotNull V value) {
        RegionatedIntIntToObjectMap<V> map = root.get(id);
        if (map == null) {
            map = new RegionatedIntIntToObjectMap<>();
            RegionatedIntIntToObjectMap<V> ret = root.putIfAbsent(id, map);
            if (ret != null) {
                map = ret;
            }
        }
        return map.put(int1, int2, value);
    }

    @Nullable
    public V putIfAbsent(UUID id, int int1, int int2, @NotNull V value) {
        RegionatedIntIntToObjectMap<V> map = root.get(id);
        if (map == null) {
            map = new RegionatedIntIntToObjectMap<>();
            RegionatedIntIntToObjectMap<V> ret = root.putIfAbsent(id, map);
            if (ret != null) {
                map = ret;
            }
        }
        return map.putIfAbsent(int1, int2, value);
    }

    public boolean replace(UUID id, int int1, int int2, @NotNull V expectedValue, @NotNull V value) {
        RegionatedIntIntToObjectMap<V> map = root.get(id);
        if (map == null) {
            map = new RegionatedIntIntToObjectMap<>();
            RegionatedIntIntToObjectMap<V> ret = root.putIfAbsent(id, map);
            if (ret != null) {
                map = ret;
            }
        }
        return map.compareAndSet(int1, int2, expectedValue, value);
    }
}
