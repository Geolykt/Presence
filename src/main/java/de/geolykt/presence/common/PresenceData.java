package de.geolykt.presence.common;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;
import java.util.zip.Adler32;
import java.util.zip.CheckedInputStream;
import java.util.zip.CheckedOutputStream;
import java.util.zip.Checksum;

import it.unimi.dsi.fastutil.io.FastByteArrayOutputStream;

/**
 * Base data holder.
 */
public class PresenceData {

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

    private final Map<DataEntry, Integer> counts = new ConcurrentHashMap<>();

    private final Map<WorldPosition, Map.Entry<UUID, Integer>> leaders = new ConcurrentHashMap<>();

    private final double recursiveTick;

    private final Map<WorldPosition, Map.Entry<UUID, Integer>> successors = new ConcurrentHashMap<>();

    private final Set<TrustEntry> trusts = new HashSet<>();

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
        Map.Entry<UUID, Integer> leader = leaders.get(new WorldPosition(world, hashPositions(x, y)));
        return leader == null || leader.getKey().equals(player) || isTrusted(leader.getKey(), player);
    }

    public boolean isOwnerOrTrusted(UUID player, UUID world, int x, int y) {
        if (bucketSize > 1) {
            x = Math.floorDiv(x, bucketSize);
            y = Math.floorDiv(y, bucketSize);
        }
        Map.Entry<UUID, Integer> leader = leaders.get(new WorldPosition(world, hashPositions(x, y)));
        return leader != null && (leader.getKey().equals(player) || isTrusted(leader.getKey(), player));
    }

    public Map.Entry<UUID, Integer> getOwner(UUID world, int x, int y) {
        if (bucketSize > 1) {
            x = Math.floorDiv(x, bucketSize);
            y = Math.floorDiv(y, bucketSize);
        }
        return leaders.get(new WorldPosition(world, hashPositions(x, y)));
    }

    public int getPresence(UUID player, UUID world, int x, int y) {
        if (bucketSize > 1) {
            x = Math.floorDiv(x, bucketSize);
            y = Math.floorDiv(y, bucketSize);
        }
        return counts.getOrDefault(new DataEntry(player, new WorldPosition(world, hashPositions(x, y))), 0);
    }

    public Map.Entry<UUID, Integer> getSuccessor(UUID world, int x, int y) {
        if (bucketSize > 1) {
            x = Math.floorDiv(x, bucketSize);
            y = Math.floorDiv(y, bucketSize);
        }
        return successors.get(new WorldPosition(world, hashPositions(x, y)));
    }

    public boolean isTrusted(UUID truster, UUID trusted) {
        return trusts.contains(new TrustEntry(truster, trusted));
    }

    public synchronized void load(Consumer<String> loggy, File dataFolder) {
        if (dataFolder.isFile()) {
            throw new RuntimeException("Loading from folder attempts to load a file!");
        }
        dataFolder.mkdirs();
        ArrayList<File> trustFiles = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            trustFiles.add(new File(dataFolder, "trust_" + i + ".dat"));
        }

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
        loadTrust(loggy, trustStreams.toArray(new byte[0][0]));

        loadStates: {
            File stateFile = new File(dataFolder, "statedb.dat");
            if (!stateFile.exists()) {
                break loadStates;
            }
            try (FileInputStream fis = new FileInputStream(stateFile)) {
                loadStateChecked(fis);
            } catch (IOException e) {
                e.printStackTrace();
                throw new IllegalStateException("Unable to load state db.", e);
            }
        }
    }

    protected void loadStateChecked(InputStream in) throws IOException {
        long checksum = ByteBuffer.wrap(in.readNBytes(8)).getLong();
        Adler32 adler32Checksum = new Adler32();
        CheckedInputStream checkedIn = new CheckedInputStream(in, adler32Checksum);
        loadState(checkedIn);
        if (adler32Checksum.getValue() != checksum) {
            throw new IOException("State invalid as it breaks the checksum.");
        }
    }


    protected void loadState(InputStream in) throws IOException {
        DataInputStream dataIn = new DataInputStream(in);

        while (dataIn.read() > 0) {
            int value = dataIn.readInt();
            UUID world = new UUID(dataIn.readLong(), dataIn.readLong());
            UUID player = new UUID(dataIn.readLong(), dataIn.readLong());
            long pos = dataIn.readLong();

            WorldPosition worldPos = new WorldPosition(world, pos);
            DataEntry entry = new DataEntry(player, worldPos);

            Map.Entry<UUID, Integer> leader = leaders.get(worldPos);
            if (leader == null || leader.getValue() < value) {
                leaders.put(worldPos, Map.entry(player, value)); // Set the leader
            } else {
                Map.Entry<UUID, Integer> successor = successors.get(worldPos);
                if (successor == null || successor.getValue() < value) {
                    // Update successor
                    successors.put(worldPos, Map.entry(player, value));
                }
            }
            if (counts.put(entry, value) != null) {
                throw new IllegalStateException("Input defined multiple entries for the same player and chunk (data curruption likely)");
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

    public void removeTrust(UUID truster, UUID trusted) {
        trusts.remove(new TrustEntry(truster, trusted));
    }

    public synchronized void save(File dataFolder) {
        // BEWARE: This method is called async, thread safety should be done carefully!
        if (dataFolder.isFile()) {
            throw new RuntimeException("Saving to folder attempts to save in file!");
        }
        dataFolder.mkdirs();
        ArrayList<File> trustFiles = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            trustFiles.add(new File(dataFolder, "trust_" + i + ".dat"));
        }

        FastByteArrayOutputStream byteOut = new FastByteArrayOutputStream(counts.size() * 50);
        Checksum checksum = new Adler32();
        CheckedOutputStream checkedOut = new CheckedOutputStream(byteOut, checksum);

        try {
            saveStateToStream(checkedOut);
        } catch (IOException e1) {
            throw new IllegalStateException("Fatal exception while serializing state.");
        }

        try (FileOutputStream fos = new FileOutputStream(new File(dataFolder, "statedb.dat"))) {
            fos.write(ByteBuffer.allocate(8).putLong(checksum.getValue()).array());
            fos.write(byteOut.array, 0, (int) byteOut.position());
        } catch (IOException e) {
            throw new IllegalStateException("Fatal exception while saving state.");
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

    protected void saveStateToStream(OutputStream out) throws IOException {
        DataOutputStream dataOut = new DataOutputStream(out);
        for (Map.Entry<DataEntry, Integer> entry : counts.entrySet()) {
            dataOut.write(1);
            dataOut.writeInt(entry.getValue().intValue());
            DataEntry de = entry.getKey();
            dataOut.writeLong(de.worldPos.world().getMostSignificantBits());
            dataOut.writeLong(de.worldPos.world().getLeastSignificantBits());
            dataOut.writeLong(de.id.getMostSignificantBits());
            dataOut.writeLong(de.id.getLeastSignificantBits());
            dataOut.writeLong(de.worldPos.chunkPos());
        }
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
            tick(id, world, dx + x, dy + y);
        }
        if (bucketSize > 1) {
            x = Math.floorDiv(x, bucketSize);
            y = Math.floorDiv(y, bucketSize);
        }
        long hashedPosition = hashPositions(x, y);
        WorldPosition worldPos = new WorldPosition(world, hashedPosition);
        DataEntry entry = new DataEntry(id, worldPos);
        int amount = counts.getOrDefault(entry, 0) + 1; // Prevent NPE by using getOrDefault

        Map.Entry<UUID, Integer> leader = leaders.get(worldPos);
        if (leader == null) { // This is a previously untouched claim, set the leader
            leaders.put(worldPos, Map.entry(id, amount));
        } else if (leader.getValue() < amount) {
            if (leader.getKey().equals(id)) { // update current leader
                leaders.put(worldPos, Map.entry(id, amount));
            } else { // change leader and put the previous leader as the successor
                successors.put(worldPos, leaders.put(worldPos, Map.entry(id, amount)));
            }
        } else {
            Map.Entry<UUID, Integer> successor = successors.get(worldPos);
            if (successor == null || successor.getValue() < amount) {
                // Update successor
                successors.put(worldPos, Map.entry(id, amount));
            }
        }
        // update counts
        counts.put(entry, amount);
    }

    static class DataEntry {
        private final UUID id;
        private final WorldPosition worldPos;

        public DataEntry(UUID player, WorldPosition worldPos) {
            this.id = player;
            this.worldPos = worldPos;
        }

        @Override
        public boolean equals(Object obj) {
            if (obj instanceof DataEntry) {
                DataEntry entry = (DataEntry) obj;
                return id.equals(entry.id) && worldPos.equals(entry.worldPos);
            }
            return false;
        }

        @Override
        public int hashCode() {
            return id.hashCode() ^ worldPos.hashCode();
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
