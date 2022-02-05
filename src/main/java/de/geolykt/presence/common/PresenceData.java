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
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;
import java.util.zip.Adler32;
import java.util.zip.CheckedInputStream;
import java.util.zip.CheckedOutputStream;
import java.util.zip.Checksum;

import org.jetbrains.annotations.NotNull;

import it.unimi.dsi.fastutil.io.FastByteArrayOutputStream;

/**
 * Base data holder.
 */
public class PresenceData {

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

    @NotNull
    private final ChunkGroupManager chunkGroups = new ChunkGroupManager();

    private final Map<DataEntry, Integer> counts = new ConcurrentHashMap<>();

    private final Map<WorldPosition, Map.Entry<UUID, Integer>> leaders = new ConcurrentHashMap<>();

    private final double recursiveTick;

    private final Map<WorldPosition, Map.Entry<UUID, Integer>> successors = new ConcurrentHashMap<>();

    public PresenceData(double tickNearbyChance) {
        recursiveTick = tickNearbyChance;
    }

    public boolean canAttack(@NotNull UUID player, @NotNull UUID world, int x, int y) {
        WorldPosition pos = new WorldPosition(world, hashPositions(x, y));
        Map.Entry<UUID, Integer> owner = leaders.get(pos);
        if (owner == null) {
            return true;
        }
        return chunkGroups.canAttack(owner.getKey(), player, pos);
    }

    public boolean canAttackNamed(@NotNull UUID player, @NotNull UUID world, int x, int y) {
        WorldPosition pos = new WorldPosition(world, hashPositions(x, y));
        Map.Entry<UUID, Integer> owner = leaders.get(pos);
        if (owner == null) {
            return true;
        }
        return chunkGroups.canAttackNamedEntities(owner.getKey(), player, pos);
    }

    public boolean canBreak(@NotNull UUID player, @NotNull UUID world, int x, int y) {
        WorldPosition pos = new WorldPosition(world, hashPositions(x, y));
        Map.Entry<UUID, Integer> owner = leaders.get(pos);
        if (owner == null) {
            return true;
        }
        return chunkGroups.canBreak(owner.getKey(), player, pos);
    }

    public boolean canBuild(@NotNull UUID player, @NotNull UUID world, int x, int y) {
        WorldPosition pos = new WorldPosition(world, hashPositions(x, y));
        Map.Entry<UUID, Integer> owner = leaders.get(pos);
        if (owner == null) {
            return true;
        }
        return chunkGroups.canBuild(owner.getKey(), player, pos);
    }

    public boolean canHarvest(@NotNull UUID player, @NotNull UUID world, int x, int y) {
        WorldPosition pos = new WorldPosition(world, hashPositions(x, y));
        Map.Entry<UUID, Integer> owner = leaders.get(pos);
        if (owner == null) {
            return true;
        }
        return chunkGroups.canHarvestCrops(owner.getKey(), player, pos);
    }

    public boolean canInteractWithBlock(@NotNull UUID player, @NotNull UUID world, int x, int y) {
        WorldPosition pos = new WorldPosition(world, hashPositions(x, y));
        Map.Entry<UUID, Integer> owner = leaders.get(pos);
        if (owner == null) {
            return true;
        }
        return chunkGroups.canInteract(owner.getKey(), player, pos);
    }

    public boolean canInteractWithEntities(@NotNull UUID player, @NotNull UUID world, int x, int y) {
        WorldPosition pos = new WorldPosition(world, hashPositions(x, y));
        Map.Entry<UUID, Integer> owner = leaders.get(pos);
        if (owner == null) {
            return true;
        }
        return chunkGroups.canInteractWithEntities(owner.getKey(), player, pos);
    }

    public boolean canTrample(@NotNull UUID player, @NotNull UUID world, int x, int y) {
        WorldPosition pos = new WorldPosition(world, hashPositions(x, y));
        Map.Entry<UUID, Integer> owner = leaders.get(pos);
        if (owner == null) {
            return true;
        }
        return chunkGroups.canTrampleCrops(owner.getKey(), player, pos);
    }

    @NotNull
    public ChunkGroupManager getChunkGroupManager() {
        return chunkGroups;
    }

    public Map.Entry<UUID, Integer> getOwner(UUID world, int x, int y) {
        return leaders.get(new WorldPosition(world, hashPositions(x, y)));
    }

    public int getPresence(UUID player, UUID world, int x, int y) {
        return counts.getOrDefault(new DataEntry(player, new WorldPosition(world, hashPositions(x, y))), 0);
    }


    public Map.Entry<UUID, Integer> getSuccessor(UUID world, int x, int y) {
        return successors.get(new WorldPosition(world, hashPositions(x, y)));
    }

    public synchronized void load(Consumer<String> loggy, File dataFolder) {
        if (dataFolder.isFile()) {
            throw new RuntimeException("Loading from folder attempts to load a file!");
        }
        dataFolder.mkdirs();

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

    protected void loadStateChecked(InputStream in) throws IOException {
        long checksum = ByteBuffer.wrap(in.readNBytes(8)).getLong();
        Adler32 adler32Checksum = new Adler32();
        CheckedInputStream checkedIn = new CheckedInputStream(in, adler32Checksum);
        loadState(checkedIn);
        if (adler32Checksum.getValue() != checksum) {
            throw new IOException("State invalid as it breaks the checksum.");
        }
    }

    public synchronized void save(File dataFolder) {
        // BEWARE: This method is called async, thread safety should be done carefully!
        if (dataFolder.isFile()) {
            throw new RuntimeException("Saving to folder attempts to save in file!");
        }
        dataFolder.mkdirs();

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
}
