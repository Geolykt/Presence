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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.Adler32;
import java.util.zip.CheckedInputStream;
import java.util.zip.CheckedOutputStream;
import java.util.zip.Checksum;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import de.geolykt.presence.common.util.PlayerAttachedPosition;
import de.geolykt.presence.common.util.PlayerAttachedScore;
import de.geolykt.presence.common.util.WorldPosition;

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

    @NotNull
    private final ChunkGroupManager chunkGroups = new ChunkGroupManager();

    private final Map<PlayerAttachedPosition, PlayerAttachedScore> counts = new ConcurrentHashMap<>();

    private final Map<WorldPosition, PlayerAttachedScore> leaders = new ConcurrentHashMap<>();

    private final double recursiveTick;

    private final Map<WorldPosition, PlayerAttachedScore> successors = new ConcurrentHashMap<>();

    public PresenceData(double tickNearbyChance) {
        recursiveTick = tickNearbyChance;
    }

    public boolean canAttack(@NotNull UUID player, @NotNull UUID world, int x, int y) {
        WorldPosition pos = new WorldPosition(world, hashPositions(x, y));
        PlayerAttachedScore record = leaders.get(pos);
        if (record == null) {
            return true;
        }
        return chunkGroups.canAttack(record.getPlayer(), player, pos);
    }

    public boolean canAttackNamed(@NotNull UUID player, @NotNull UUID world, int x, int y) {
        WorldPosition pos = new WorldPosition(world, hashPositions(x, y));
        PlayerAttachedScore record = leaders.get(pos);
        if (record == null) {
            return true;
        }
        return chunkGroups.canAttackNamedEntities(record.getPlayer(), player, pos);
    }

    public boolean canBreak(@NotNull UUID player, @NotNull UUID world, int x, int y) {
        WorldPosition pos = new WorldPosition(world, hashPositions(x, y));
        PlayerAttachedScore record = leaders.get(pos);
        if (record == null) {
            return true;
        }
        return chunkGroups.canBreak(record.getPlayer(), player, pos);
    }

    public boolean canBuild(@NotNull UUID player, @NotNull UUID world, int x, int y) {
        WorldPosition pos = new WorldPosition(world, hashPositions(x, y));
        PlayerAttachedScore record = leaders.get(pos);
        if (record == null) {
            return true;
        }
        return chunkGroups.canBuild(record.getPlayer(), player, pos);
    }

    public boolean canHarvest(@NotNull UUID player, @NotNull UUID world, int x, int y) {
        WorldPosition pos = new WorldPosition(world, hashPositions(x, y));
        PlayerAttachedScore record = leaders.get(pos);
        if (record == null) {
            return true;
        }
        return chunkGroups.canHarvestCrops(record.getPlayer(), player, pos);
    }

    public boolean canInteractWithBlock(@NotNull UUID player, @NotNull UUID world, int x, int y) {
        WorldPosition pos = new WorldPosition(world, hashPositions(x, y));
        PlayerAttachedScore record = leaders.get(pos);
        if (record == null) {
            return true;
        }
        return chunkGroups.canInteract(record.getPlayer(), player, pos);
    }

    public boolean canInteractWithEntities(@NotNull UUID player, @NotNull UUID world, int x, int y) {
        WorldPosition pos = new WorldPosition(world, hashPositions(x, y));
        PlayerAttachedScore record = leaders.get(pos);
        if (record == null) {
            return true;
        }
        return chunkGroups.canInteractWithEntities(record.getPlayer(), player, pos);
    }

    public boolean canTrample(@NotNull UUID player, @NotNull UUID world, int x, int y) {
        WorldPosition pos = new WorldPosition(world, hashPositions(x, y));
        PlayerAttachedScore record = leaders.get(pos);
        if (record == null) {
            return true;
        }
        return chunkGroups.canTrampleCrops(record.getPlayer(), player, pos);
    }

    @NotNull
    public ChunkGroupManager getChunkGroupManager() {
        return chunkGroups;
    }

    @Nullable
    public PlayerAttachedScore getOwner(@NotNull UUID world, int x, int y) {
        return leaders.get(new WorldPosition(world, hashPositions(x, y)));
    }

    public int getPresence(@NotNull UUID player, @NotNull UUID world, int x, int y) {
        PlayerAttachedScore record = counts.get(new PlayerAttachedPosition(player, new WorldPosition(world, hashPositions(x, y))));
        if (record == null) {
            return 0;
        }
        return record.score().get();
    }


    @Nullable
    public PlayerAttachedScore getSuccessor(UUID world, int x, int y) {
        return successors.get(new WorldPosition(world, hashPositions(x, y)));
    }

    public synchronized void load(@NotNull File dataFolder) {
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
                throw new IllegalStateException("Unable to load state db.", e);
            }
        }

        loadChunkGroups: {
            File chunkGroupsFile = new File(dataFolder, "chunkgroups.dat");
            if (!chunkGroupsFile.exists()) {
                break loadChunkGroups;
            }
            try (FileInputStream fis = new FileInputStream(chunkGroupsFile)) {
                chunkGroups.loadSafely(fis);
            } catch (IOException e) {
                throw new IllegalStateException("Unable to load chunk groups.", e);
            }
        }
    }

    protected void loadState(@NotNull InputStream in) throws IOException {
        DataInputStream dataIn = new DataInputStream(in);

        while (dataIn.read() > 0) {
            int value = dataIn.readInt();
            UUID world = new UUID(dataIn.readLong(), dataIn.readLong());
            UUID player = new UUID(dataIn.readLong(), dataIn.readLong());
            long pos = dataIn.readLong();

            WorldPosition worldPos = new WorldPosition(world, pos);
            PlayerAttachedPosition entry  = new PlayerAttachedPosition(player, worldPos);

            PlayerAttachedScore oldLeader = leaders.get(worldPos);
            PlayerAttachedScore loadedPlayer = new PlayerAttachedScore(player, new AtomicInteger(value));
            if (oldLeader == null || oldLeader.score().get() < value) {
                leaders.put(worldPos, loadedPlayer); // Set the leader to a more accurate value
            } else {
                PlayerAttachedScore successor = successors.get(worldPos);
                if (successor == null || successor.score().get() < value) {
                    // Update successor
                    successors.put(worldPos, loadedPlayer);
                }
            }
            if (counts.putIfAbsent(entry, loadedPlayer) != null) {
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
            throw new IllegalStateException("Fatal exception while serializing state.", e1);
        }

        try (FileOutputStream fos = new FileOutputStream(new File(dataFolder, "statedb.dat"))) {
            fos.write(ByteBuffer.allocate(8).putLong(checksum.getValue()).array());
            fos.write(byteOut.array, 0, (int) byteOut.position());
        } catch (IOException e) {
            throw new IllegalStateException("Fatal exception while saving state.", e);
        }

        try (FileOutputStream fos = new FileOutputStream(new File(dataFolder, "chunkgroups.dat"))) {
            chunkGroups.saveSafely(fos);
        } catch (IOException e) {
            throw new IllegalStateException("Fatal exception while saving state.", e);
        }
    }

    protected void saveStateToStream(OutputStream out) throws IOException {
        DataOutputStream dataOut = new DataOutputStream(out);
        for (Map.Entry<PlayerAttachedPosition, PlayerAttachedScore> entry : counts.entrySet()) {
            dataOut.write(1);
            dataOut.writeInt(entry.getValue().score().get());
            PlayerAttachedPosition de = entry.getKey();
            dataOut.writeLong(de.pos().world().getMostSignificantBits());
            dataOut.writeLong(de.pos().world().getLeastSignificantBits());
            dataOut.writeLong(de.player().getMostSignificantBits());
            dataOut.writeLong(de.player().getLeastSignificantBits());
            dataOut.writeLong(de.pos().chunkPos());
        }
    }

    /**
     * Increases the presence of a given player by one.
     * This method can be called concurrently, however other methods in this class may have not been
     * built for this.
     *
     * @param id A unique identifier that identifies a user.
     * @param world The UUID of the world of the chunk
     * @param x The X-Coordinate of the chunk (in chunks)
     * @param y The Y-Coordinate of the chunk (in chunks)
     */
    public void tick(@NotNull UUID id, @NotNull UUID world, int x, int y) {
        if (recursiveTick > 0.0 && recursiveTick > ThreadLocalRandom.current().nextDouble(1.0)) {
            int dx = ThreadLocalRandom.current().nextInt(-3, 4);
            int dy = ThreadLocalRandom.current().nextInt(-3, 4);
            tick(id, world, dx + x, dy + y);
        }
        long hashedPosition = hashPositions(x, y);
        WorldPosition worldPos = new WorldPosition(world, hashedPosition);
        PlayerAttachedPosition entry = new PlayerAttachedPosition(id, worldPos);
        PlayerAttachedScore tickedRecord = counts.get(entry);
        if (tickedRecord == null) {
            tickedRecord = new PlayerAttachedScore(world, new AtomicInteger(0));
            PlayerAttachedScore retain = counts.putIfAbsent(entry, tickedRecord);
            if (retain != null) { // Race condition
                tickedRecord = retain;
            }
        }
        tickedRecord.score().getAndIncrement();

        do {
            PlayerAttachedScore oldLeader = leaders.get(worldPos);
            if (oldLeader == null) { // This is a previously untouched claim, set the leader
                oldLeader = leaders.putIfAbsent(worldPos, tickedRecord);
            }

            if (oldLeader != null && tickedRecord != oldLeader) {
                if (oldLeader.score().get() < tickedRecord.score().get()) {
                    if (!leaders.replace(worldPos, oldLeader, tickedRecord)) {
                        continue; // The old value changed in the meantime: let's have another poke at it
                    }
                    ChunkGroup group = chunkGroups.getGroupAt(worldPos);
                    if (group != null) {
                        chunkGroups.removeChunk(group, worldPos);
                    }
                    do {
                        PlayerAttachedScore oldSuccessor = successors.get(worldPos);
                        if (oldSuccessor == null) { // There is no successor, so we can easily change it now
                            oldSuccessor = successors.putIfAbsent(worldPos, oldLeader);
                        }
                        if (oldSuccessor != null && oldLeader != oldSuccessor
                                && oldSuccessor.score().get() < oldLeader.score().get()
                                && !successors.replace(worldPos, oldSuccessor, oldLeader)) {
                            continue; // The old value changed in the meantime: let's have another poke at it
                        }
                    } while(false);
                } else {
                    do {
                        PlayerAttachedScore oldSuccessor = successors.get(worldPos);
                        if (oldSuccessor == null) { // There is no successor, so we can easily change it now
                            oldSuccessor = successors.putIfAbsent(worldPos, tickedRecord);
                        }
                        if (oldSuccessor != null && tickedRecord != oldSuccessor
                                && oldSuccessor.score().get() < tickedRecord.score().get()
                                && !successors.replace(worldPos, oldSuccessor, tickedRecord)) {
                            continue; // The old value changed in the meantime: let's have another poke at it
                        }
                    } while(false);
                }
            }
        } while(false); // While(true)'s alter ego
    }
}
