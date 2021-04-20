package de.geolykt.presence.common;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

/**
 * Base data holder.
 */
public class PresenceData {

    protected static int getCommonArrayLength(byte[][] arrays) {
        int commonLen = 0;
        int commonAmt = 0;
        for (int i = 0; i < arrays.length; i++) {
            int amt = 0;
            for (int j = 0; j < arrays.length; j++) {
                if (arrays[j].length == arrays[i].length) {
                    amt++;
                }
            }
            if (amt > commonAmt) {
                commonAmt = amt;
                commonLen = arrays[i].length;
            }
        }
        return commonLen;
    }

    public static long hashPositions(int x, int y) {
        // We make use of (y & 0xFFFFFFFFL) as otherwise y values such as -1 would completely override the x value.
        // This is because `long | int` automatically casts the int to a long, where as the cast is by decimal value
        return (((long) x) << 32) | (y & 0xFFFFFFFFL);
    }

    /**
     * Size of the buckets in chunks.
     * The higher the value, the higher the granularity
     */
    private final int bucketSize;
    // Do not forget to synchronise what needs to be synchronised!
    private final HashMap<DataEntry, Integer> counts = new HashMap<>();

    private final HashMap<Map.Entry<UUID, Long>, Map.Entry<UUID, Integer>> leaders = new HashMap<>();

    private final double recursiveTick;

    private final HashMap<Map.Entry<UUID, Long>, Map.Entry<UUID, Integer>> successors = new HashMap<>();

    private final HashSet<TrustEntry> trusts = new HashSet<>();

    public PresenceData(int claimSize, double tickNearbyChance) {
        bucketSize = claimSize;
        recursiveTick = tickNearbyChance;
    }

    public void addTrust(UUID truster, UUID trusted) {
        trusts.add(new TrustEntry(truster, trusted));
    }

    public boolean canUse(UUID player, UUID world, int x, int y) {
        if (bucketSize > 1) {
            x = Math.floorDiv(x, bucketSize);
            y = Math.floorDiv(y, bucketSize);
        }
        Map.Entry<UUID, Integer> leader = leaders.get(Map.entry(world, hashPositions(x, y)));
        return leader == null || leader.getKey().equals(player) || isTrusted(leader.getKey(), player);
    }

    public boolean isOwnerOrTrusted(UUID player, UUID world, int x, int y) {
        if (bucketSize > 1) {
            x = Math.floorDiv(x, bucketSize);
            y = Math.floorDiv(y, bucketSize);
        }
        Map.Entry<UUID, Integer> leader = leaders.get(Map.entry(world, hashPositions(x, y)));
        return leader != null && (leader.getKey().equals(player) || isTrusted(leader.getKey(), player));
    }

    public Map.Entry<UUID, Integer> getOwner(UUID world, int x, int y) {
        if (bucketSize > 1) {
            x = Math.floorDiv(x, bucketSize);
            y = Math.floorDiv(y, bucketSize);
        }
        return leaders.get(Map.entry(world, hashPositions(x, y)));
    }

    public int getPresence(UUID player, UUID world, int x, int y) {
        if (bucketSize > 1) {
            x = Math.floorDiv(x, bucketSize);
            y = Math.floorDiv(y, bucketSize);
        }
        synchronized(counts) {
            return counts.getOrDefault(new DataEntry(player, world, x, y), 0);
        }
    }

    public Map.Entry<UUID, Integer> getSuccessor(UUID world, int x, int y) {
        if (bucketSize > 1) {
            x = Math.floorDiv(x, bucketSize);
            y = Math.floorDiv(y, bucketSize);
        }
        return successors.get(Map.entry(world, hashPositions(x, y)));
    }

    public boolean isTrusted(UUID truster, UUID trusted) {
        return trusts.contains(new TrustEntry(truster, trusted));
    }

    public synchronized void load(Consumer<String> loggy, File dataFolder) {
        if (dataFolder.isFile()) {
            throw new RuntimeException("Loading from folder attempts to load a file!");
        }
        dataFolder.mkdirs();
        ArrayList<File> stateFiles = new ArrayList<>();
        ArrayList<File> trustFiles = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            stateFiles.add(new File(dataFolder, "state_" + i + ".dat"));
            trustFiles.add(new File(dataFolder, "trust_" + i + ".dat"));
        }
        ArrayList<Object> stateStreams = new ArrayList<>();
        for (File f : stateFiles) {
            if (f.exists()) {
                try (FileInputStream fis = new FileInputStream(f)) {
                    stateStreams.add(fis.readAllBytes());
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
        loadState(loggy, stateStreams.toArray(new byte[0][0]));
        ArrayList<Object> trustStreams = new ArrayList<>();
        for (File f : trustFiles) {
            if (f.exists()) {
                try (FileInputStream fis = new FileInputStream(f)) {
                    trustStreams.add(fis.readAllBytes());
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
        loadState(loggy, trustStreams.toArray(new byte[0][0]));
    }


    /**
     * Loads the data of several streams.
     * The implementation can make use of multiple algorithms to verify the integrity of the streams
     * and use the stream with the best integrity.
     * The default implementation however just uses a crude checksum and length verification by looking
     * at the most common length of the streams.
     *
     * @param warnLogger Our crude replacement to be logger independent. All warnings will call this consumer.
     * @param streams The input data arrays
     */
    protected void loadState(Consumer<String> warnLogger, byte[]... streams) {
        if (streams.length == 0) {
            if (warnLogger != null) {
                warnLogger.accept("No input data to load!");
            }
            return;
        }
        ArrayList<Integer> useableStreams = new ArrayList<>();
        int commonLength = getCommonArrayLength(streams);
        if (commonLength < 9) {
            return;
        }
        for (int i = 0; i < streams.length; i++) {
            if (streams[i].length == commonLength) {
                useableStreams.add(i);
            } else {
                if (warnLogger != null) {
                    warnLogger.accept("Data array at index " + i + " does not match the common length (possible corruption)!");
                }
            }
        }
        ByteBuffer[] readers = new ByteBuffer[streams.length];
        ArrayList<Integer> toRemove = new ArrayList<>();
        for (Integer i : useableStreams) {
            ByteBuffer buffer = ByteBuffer.wrap(streams[i]);
            long definedChecksum = buffer.getLong();
            long calculatedChecksum = 0;
            while (buffer.hasRemaining()) {
                calculatedChecksum += buffer.get();
            }
            if (definedChecksum != calculatedChecksum) {
                toRemove.add(i);
                if (warnLogger != null) {
                    warnLogger.accept("Data array at index " + i + " does not match the expected checksum (data corrupted)!");
                }
            } else {
                readers[i] = ByteBuffer.wrap(streams[i], 8, commonLength - 8);
            }
        }
        toRemove.forEach(useableStreams::remove);
        toRemove = null; // To prevent accidental reuse
        if (useableStreams.size() == 0) {
            throw new IllegalStateException("All input streams were invalidated due to common length checks and the data checksum."
                    + "Corruption of the data streams is likely.");
        }
        // Technically we could use the other streams to prevent other issues with the 
        ByteBuffer buffer = readers[useableStreams.get(0)];
        if ((buffer.remaining() % 44) != 0) {
            throw new IllegalStateException("The selected input stream has an invalid length.");
        }
        while (buffer.hasRemaining()) {
            // The actual deserialisation magic happens here
            long position = buffer.getLong();
            long mostSigBitsPlyr = buffer.getLong();
            long leastSigBitsPlyr = buffer.getLong();
            long mostSigBitsWorld = buffer.getLong();
            long leastSigBitsWorld = buffer.getLong();
            UUID player = new UUID(mostSigBitsPlyr, leastSigBitsPlyr);
            UUID world = new UUID(mostSigBitsWorld, leastSigBitsWorld);
            int presence = buffer.getInt();
            Map.Entry<UUID, Integer> leader = leaders.get(Map.entry(world, position));
            if (leader == null || leader.getValue() < presence) {
                leaders.put(Map.entry(world, position), Map.entry(player, presence)); // Set the leader
            } else {
                Map.Entry<UUID, Integer> successor = successors.get(Map.entry(world, position));
                if (successor == null || successor.getValue() < presence) {
                    // Update successor
                    successors.put(Map.entry(world, position), Map.entry(player, presence));
                }
            }
            if (counts.put(new DataEntry(player, world, position), presence) != null) {
                if (warnLogger != null) {
                    warnLogger.accept("Data array at index 0 defines multiple entries for the same player and chunk (data corruption likely)!");
                }
            }
        }
    }

    /**
     * Loads the data of several streams.
     * The implementation can make use of multiple algorithms to verify the integrity of the streams
     * and use the stream with the best integrity.
     * The default implementation however just uses a crude checksum and length verification by looking
     * at the most common length of the streams.
     *
     * @param warnLogger Our crude replacement to be logger independent. All warnings will call this consumer.
     * @param streams The input data arrays
     */
    protected void loadTrust(Consumer<String> warnLogger, byte[]... streams) {
        if (streams.length == 0) {
            if (warnLogger != null) {
                warnLogger.accept("No input data to load!");
            }
            return;
        }
        ArrayList<Integer> useableStreams = new ArrayList<>();
        int commonLength = getCommonArrayLength(streams);
        if (commonLength < 9) {
            return;
        }
        for (int i = 0; i < streams.length; i++) {
            if (streams[i].length == commonLength) {
                useableStreams.add(i);
            } else {
                if (warnLogger != null) {
                    warnLogger.accept("Data array at index " + i + " does not match the common length (possible corruption)!");
                }
            }
        }
        ByteBuffer[] readers = new ByteBuffer[streams.length];
        ArrayList<Integer> toRemove = new ArrayList<>();
        for (Integer i : useableStreams) {
            ByteBuffer buffer = ByteBuffer.wrap(streams[i]);
            long definedChecksum = buffer.getLong();
            long calculatedChecksum = 0;
            while (buffer.hasRemaining()) {
                calculatedChecksum += buffer.get();
            }
            if (definedChecksum != calculatedChecksum) {
                toRemove.add(i);
                if (warnLogger != null) {
                    warnLogger.accept("Data array at index " + i + " does not match the expected checksum (data corrupted)!");
                }
            } else {
                readers[i] = ByteBuffer.wrap(streams[i], 8, commonLength - 8);
            }
        }
        toRemove.forEach(useableStreams::remove);
        toRemove = null; // To prevent accidental reuse
        if (useableStreams.size() == 0) {
            throw new IllegalStateException("All input streams were invalidated due to common length checks and the data checksum."
                    + "Corruption of the data streams is likely.");
        }
        // Technically we could use the other streams to prevent other issues with the 
        ByteBuffer buffer = readers[useableStreams.get(0)];
        if ((buffer.remaining() % 32) != 0) {
            throw new IllegalStateException("The selected input stream has an invalid length.");
        }
        while (buffer.hasRemaining()) {
            // The actual deserialisation magic happens here
            long trusterMostSigBits = buffer.getLong();
            long trusterLeastSigBits = buffer.getLong();
            long trustedMostSigBits = buffer.getLong();
            long trustedLeastSigBits = buffer.getLong();
            TrustEntry entry = new TrustEntry(new UUID(trusterMostSigBits, trusterLeastSigBits), new UUID(trustedMostSigBits, trustedLeastSigBits));
            if (!trusts.add(entry) && warnLogger != null) {
                warnLogger.accept("Tried to trust the same pair twice. Data corruption likely.");
            }
        }
    }

    public void removeTrust(UUID truster, UUID trusted) {
        trusts.remove(new TrustEntry(truster, trusted));
    }

    public synchronized void save(File dataFolder) {
        // BEWARE: This method is called async, thread safety should be done carefully!
        if (dataFolder.isFile()) {
            throw new RuntimeException("Saving to folder attempts to save in file!");
        }
        dataFolder.mkdirs();
        ArrayList<File> stateFiles = new ArrayList<>();
        ArrayList<File> trustFiles = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            stateFiles.add(new File(dataFolder, "state_" + i + ".dat"));
            trustFiles.add(new File(dataFolder, "trust_" + i + ".dat"));
        }
        byte[] data = saveStateToArray();
        for (File file : stateFiles) {
            try (FileOutputStream fos = new FileOutputStream(file)) {
                fos.write(data);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        byte[] trust = saveTrustToArray();
        for (File file : trustFiles) {
            try (FileOutputStream fos = new FileOutputStream(file)) {
                fos.write(trust);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    protected byte[] saveStateToArray() {
        ByteBuffer buffer;
        int len;
        synchronized (counts) {
            len = 44 * counts.size();
            buffer = ByteBuffer.allocate(len);
            for (Map.Entry<DataEntry, Integer> entry : counts.entrySet()) {
                long position = entry.getKey().pos;
                long playerMostSigBits = entry.getKey().id.getMostSignificantBits();
                long playerLeastSigBits = entry.getKey().id.getLeastSignificantBits();
                long worldMostSigBits = entry.getKey().world.getMostSignificantBits();
                long worldLeastSigBits = entry.getKey().world.getLeastSignificantBits();
                int presence = entry.getValue();

                buffer.putLong(position)
                    .putLong(playerMostSigBits).putLong(playerLeastSigBits)
                    .putLong(worldMostSigBits).putLong(worldLeastSigBits)
                    .putInt(presence);
            }
        }
        byte[] out = new byte[len + 8];
        byte[] raw = buffer.array();
        System.arraycopy(raw, 0, out, 8, len);
        long checksumValue = 0;
        for (int i = 0; i < raw.length; i++) {
            checksumValue += raw[i];
        }
        byte[] checksum = ByteBuffer.allocate(8).putLong(checksumValue).array();
        System.arraycopy(checksum, 0, out, 0, 8);
        return out;
    }

    protected byte[] saveTrustToArray() {
        int len;
        ByteBuffer buffer;
        synchronized (leaders) {
            len = 32 * trusts.size();
            buffer = ByteBuffer.allocate(len);
            for (TrustEntry entry : trusts) {
                long trusterMostSigBits = entry.getKey().getMostSignificantBits();
                long trusterLeastSigBits = entry.getKey().getLeastSignificantBits();
                long trustedMostSigBits = entry.getValue().getMostSignificantBits();
                long trustedLeastSigBits = entry.getValue().getLeastSignificantBits();
                buffer.putLong(trusterMostSigBits).putLong(trusterLeastSigBits).putLong(trustedMostSigBits).putLong(trustedLeastSigBits);
            }
        }
        byte[] out = new byte[len + 8];
        byte[] raw = buffer.array();
        System.arraycopy(raw, 0, out, 8, len);
        long checksumValue = 0;
        for (int i = 0; i < raw.length; i++) {
            checksumValue += raw[i];
        }
        byte[] checksum = ByteBuffer.allocate(8).putLong(checksumValue).array();
        System.arraycopy(checksum, 0, out, 0, 8);
        return out;
    }

    /**
     * Increases the presence of a given player by one.
     *
     * @param id A unique identifier that identifies a user.
     * @param world The UUID of the world of the chunk
     * @param x The X-Coordinate of the chunk (in chunks)
     * @param y The Y-Coordinate of the chunk (in chunks)
     */
    public void tick(UUID id, UUID world, int x, int y) {
        if (recursiveTick > 0.0 && recursiveTick > ThreadLocalRandom.current().nextDouble(1.0)) {
            int dx = ThreadLocalRandom.current().nextInt(-3, 4);
            int dy = ThreadLocalRandom.current().nextInt(-3, 4);
            tick(id, world, dx + dx, dy + y);
        }
        if (bucketSize > 1) {
            x = Math.floorDiv(x, bucketSize);
            y = Math.floorDiv(y, bucketSize);
        }
        Long hashedPosition = hashPositions(x, y);
        DataEntry entry = new DataEntry(id, world, hashedPosition);
        Integer amount = counts.getOrDefault(entry, 0) + 1; // Prevent NPE by using getOrDefault
        Map.Entry<UUID, Integer> leader = leaders.get(Map.entry(world, hashedPosition));
        if (leader == null) { // This is a previously untouched claim, set the leader
            leaders.put(Map.entry(world, hashedPosition), Map.entry(id, amount));
        } else if (leader.getValue() < amount) {
            if (leader.getKey().equals(id)) { // update current leader
                leaders.put(Map.entry(world, hashedPosition), Map.entry(id, amount));
            } else { // change leader and put the previous leader as the successor
                successors.put(Map.entry(world, hashedPosition), leaders.put(Map.entry(world, hashedPosition), Map.entry(id, amount)));
            }
        } else {
            Map.Entry<UUID, Integer> successor = successors.get(Map.entry(world, hashedPosition));
            if (successor == null || successor.getValue() < amount) {
                // Update successor
                successors.put(Map.entry(world, hashedPosition), Map.entry(id, amount));
            }
        }
        // update counts
        synchronized (counts) { // This needs to be synchronised as we are saving this list asynchronously.
            counts.put(entry, amount);
        }
    }

    static class DataEntry {
        private final UUID id;
        private final long pos;
        private final UUID world;

        public DataEntry(UUID player, UUID world, int x, int y) {
            id = player;
            pos = hashPositions(x,y);
            this.world = world;
        }

        public DataEntry(UUID player, UUID world, long pos) {
            id = player;
            this.pos = pos;
            this.world = world;
        }

        @Override
        public boolean equals(Object obj) {
            if (obj instanceof DataEntry) {
                DataEntry entry = (DataEntry) obj;
                return entry.pos == pos && id.equals(entry.id) && world.equals(entry.world);
            }
            return false;
        }

        @Override
        public int hashCode() {
            return Objects.hash(id, pos, world);
        }
    }

    static class TrustEntry implements Map.Entry<UUID, UUID> {
        private final UUID trusted;
        private final UUID truster;

        public TrustEntry(UUID truster, UUID trusted) {
            this.trusted = trusted;
            this.truster = truster;
        }

        @Override
        public boolean equals(Object obj) {
            if (obj == this) {
                return true;
            }
            if (obj instanceof TrustEntry) {
                return ((TrustEntry) obj).trusted.equals(trusted) && ((TrustEntry) obj).truster.equals(truster);
            }
            return false;
        }

        @Override
        public UUID getKey() {
            return truster;
        }

        @Override
        public UUID getValue() {
            return trusted;
        }

        @Override
        public int hashCode() {
            return (truster.hashCode() << 16) ^ (trusted.hashCode() >>> 16);
        }

        @Override
        public UUID setValue(UUID value) {
            throw new UnsupportedOperationException("The implementation does not allow to change the trusted person.");
        }
    }
}
