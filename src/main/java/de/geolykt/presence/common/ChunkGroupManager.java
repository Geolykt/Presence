package de.geolykt.presence.common;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Collection;
import java.util.ConcurrentModificationException;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.Adler32;
import java.util.zip.CheckedInputStream;
import java.util.zip.CheckedOutputStream;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.google.common.primitives.Longs;
import com.google.common.primitives.Shorts;

import de.geolykt.presence.common.util.ElementAlreadyExistsException;
import de.geolykt.presence.common.util.PlayerAttachedString;
import de.geolykt.presence.common.util.WorldPosition;

public class ChunkGroupManager {

    protected static final short CURRENT_VERSION = 1;
    private final Map<WorldPosition, ChunkGroup> groupedChunks = new ConcurrentHashMap<>();
    private final Map<PlayerAttachedString, ChunkGroup> groupNames = new ConcurrentHashMap<>();
    private final Map<UUID, PermissionMatrix> playerDefaults = new ConcurrentHashMap<>();
    private final Map<UUID, Set<UUID>> trustedPlayers = new ConcurrentHashMap<>();
    private final Map<UUID, Set<ChunkGroup>> playerGroups = new ConcurrentHashMap<>();

    /**
     * Adds the player "trusted" to the list of trusted players of the player "truster".
     * This method is fully safe to use in a concurrent environment, provided it isn't within the loading phase.
     *
     * @param truster The trusting player
     * @param trusted The player that is trusted by the trusting player
     * @return True if the trusting player did not already trust the trusted player. See {@link Set#add(Object)}.
     */
    public boolean addTrustedPlayer(@NotNull UUID truster, @NotNull UUID trusted) {
        Set<UUID> trustedPlayers = this.trustedPlayers.get(truster);
        if (trustedPlayers == null) {
            trustedPlayers = ConcurrentHashMap.newKeySet();
            Set<UUID> var10001 = this.trustedPlayers.putIfAbsent(trusted, trustedPlayers);
            if (var10001 != null) { // Race condition
                trustedPlayers = var10001;
            }
        }
        return trustedPlayers.add(trusted);
    }

    /**
     * Checks whether the given player has the attack permission for the given chunk.
     * If there is no chunk group at the specified location then the permission according to the
     * owner defaults are returned. If owner is null true is returned.
     * This method is fully safe to use in a concurrent environment, provided it isn't within the loading phase.
     *
     * @param owner The owner of the chunk. Used for example when there is no chunk group
     * @param player The player to check access
     * @param pos The position of the chunk
     * @return Whether the player can attack entities in the chunk
     * @see PermissionMatrix#canAttack(int)
     */
    public boolean canAttack(@Nullable UUID owner, @NotNull UUID player, @NotNull WorldPosition pos) {
        if (owner == null) {
            return true;
        }
        ChunkGroup group = groupedChunks.get(pos);
        PermissionMatrix perms = getPermissionMatrix(owner, group);
        int type = getRelationship(owner, player, group);
        return perms.canAttack(type);
    }

    /**
     * Checks whether the given player has the permissions required to attack nametagged entities in the provided chunk.
     * If there is no chunk group at the specified location then the permission according to the
     * owner defaults are returned. If owner is null true is returned.
     * This method is fully safe to use in a concurrent environment, provided it isn't within the loading phase.
     *
     * @param owner The owner of the chunk. Used for example when there is no chunk group
     * @param player The player to check access
     * @param pos The position of the chunk
     * @return Whether the player can attack named entities in the chunk
     * @see PermissionMatrix#canAttackNamedEntities(int)
     */
    public boolean canAttackNamedEntities(@Nullable UUID owner, @NotNull UUID player, @NotNull WorldPosition pos) {
        if (owner == null) {
            return true;
        }
        ChunkGroup group = groupedChunks.get(pos);
        PermissionMatrix perms = getPermissionMatrix(owner, group);
        int type = getRelationship(owner, player, group);
        return perms.canAttackNamedEntities(type);
    }

    /**
     * Checks whether the given player has break permission for the given chunk.
     * If there is no chunk group at the specified location then the permission according to the
     * owner defaults are returned. If owner is null true is returned.
     * This method is fully safe to use in a concurrent environment, provided it isn't within the loading phase.
     *
     * @param owner The owner of the chunk. Used for example when there is no chunk group
     * @param player The player to check access
     * @param pos The position of the chunk
     * @return Whether the player can break blocks in the chunk
     * @see PermissionMatrix#canDestroy(int)
     */
    public boolean canBreak(@Nullable UUID owner, @NotNull UUID player, @NotNull WorldPosition pos) {
        if (owner == null) {
            return true;
        }
        ChunkGroup group = groupedChunks.get(pos);
        PermissionMatrix perms = getPermissionMatrix(owner, group);
        int type = getRelationship(owner, player, group);
        return perms.canDestroy(type);
    }

    /**
     * Checks whether the given player has the build permission for the given chunk.
     * If there is no chunk group at the specified location then the permission according to the
     * owner defaults are returned. If owner is null true is returned.
     * This method is fully safe to use in a concurrent environment, provided it isn't within the loading phase.
     *
     * @param owner The owner of the chunk. Used for example when there is no chunk group
     * @param player The player to check access
     * @param pos The position of the chunk
     * @return Whether the player can build blocks in the chunk
     * @see PermissionMatrix#canBuild(int)
     */
    public boolean canBuild(@Nullable UUID owner, @NotNull UUID player, @NotNull WorldPosition pos) {
        if (owner == null) {
            return true;
        }
        ChunkGroup group = groupedChunks.get(pos);
        PermissionMatrix perms = getPermissionMatrix(owner, group);
        int type = getRelationship(owner, player, group);
        return perms.canBuild(type);
    }

    /**
     * Checks whether explosions are enabled in the given chunk or for the given owner player.
     * If there is no chunk group at the specified location then the permission according to the
     * owner defaults are returned. If owner is null true is returned.
     * This method is fully safe to use in a concurrent environment, provided it isn't within the loading phase.
     *
     * @param owner The owner of the chunk. Used for example when there is no chunk group at the given position
     * @param pos The position of the chunk
     * @return Whether blocks can be broken due to explosions.
     * @see PermissionMatrix#getExplosionsEnabled()
     */
    public boolean canExplode(@Nullable UUID owner, @NotNull WorldPosition pos) {
        if (owner == null) {
            return true;
        }
        ChunkGroup group = groupedChunks.get(pos);
        PermissionMatrix perms = getPermissionMatrix(owner, group);
        return perms.getExplosionsEnabled();
    }

    /**
     * Checks whether the given player is able to harvest crops within the given chunk.
     * Melon and Pumpkin stems are not counted as crops by default. This permission can be helpful
     * in public farms.
     * If there is no chunk group at the specified location then the permission according to the
     * owner defaults are returned. If owner is null true is returned.
     * This method is fully safe to use in a concurrent environment, provided it isn't within the loading phase.
     *
     * @param owner The owner of the chunk. Used for example when there is no chunk group
     * @param player The player to check access
     * @param pos The position of the chunk
     * @return True if the player can harvest crops in the chunk
     * @see PermissionMatrix#canHarvestCrops(int)
     */
    public boolean canHarvestCrops(@Nullable UUID owner, @NotNull UUID player, @NotNull WorldPosition pos) {
        if (owner == null) {
            return true;
        }
        ChunkGroup group = groupedChunks.get(pos);
        PermissionMatrix perms = getPermissionMatrix(owner, group);
        int type = getRelationship(owner, player, group);
        return perms.canHarvestCrops(type);
    }

    /**
     * Checks whether the given player has the permission to interact with blocks in the given chunk.
     * If there is no chunk group at the specified location then the permission according to the
     * owner defaults are returned. If owner is null true is returned.
     * This method is fully safe to use in a concurrent environment, provided it isn't within the loading phase.
     *
     * @param owner The owner of the chunk. Used for example when there is no chunk group
     * @param player The player to check access
     * @param pos The position of the chunk
     * @return Whether the player can interact at blocks in the chunk
     * @see PermissionMatrix#canBuild(int)
     */
    public boolean canInteract(@Nullable UUID owner, @NotNull UUID player, @NotNull WorldPosition pos) {
        if (owner == null) {
            return true;
        }
        ChunkGroup group = groupedChunks.get(pos);
        PermissionMatrix perms = getPermissionMatrix(owner, group);
        int type = getRelationship(owner, player, group);
        return perms.canInteract(type);
    }

    /**
     * Checks whether the given player has the permission to interact with entities within the given chunk.
     * If there is no chunk group at the specified location then the permission according to the
     * owner defaults are returned. If owner is null true is returned.
     * This method is fully safe to use in a concurrent environment, provided it isn't within the loading phase.
     *
     * @param owner The owner of the chunk. Used for example when there is no chunk group
     * @param player The player to check access
     * @param pos The position of the chunk
     * @return Whether the player can interact at entities in the chunk
     * @see PermissionMatrix#canAttack(int)
     */
    public boolean canInteractWithEntities(@Nullable UUID owner, @NotNull UUID player, @NotNull WorldPosition pos) {
        if (owner == null) {
            return true;
        }
        ChunkGroup group = groupedChunks.get(pos);
        PermissionMatrix perms = getPermissionMatrix(owner, group);
        int type = getRelationship(owner, player, group);
        return perms.canInteractWithEntity(type);
    }

    /**
     * Checks whether the given player is able to trample crops (and farmland overall) within the given chunk.
     * If there is no chunk group at the specified location then the permission according to the
     * owner defaults are returned. If owner is null true is returned.
     * This method is fully safe to use in a concurrent environment, provided it isn't within the loading phase.
     *
     * @param owner The owner of the chunk. Used for example when there is no chunk group
     * @param player The player to check access
     * @param pos The position of the chunk
     * @return True if the player can trample crops and farmland in the chunk
     * @see PermissionMatrix#canTrampleCrops(int)
     */
    public boolean canTrampleCrops(@Nullable UUID owner, @NotNull UUID player, @NotNull WorldPosition pos) {
        if (owner == null) {
            return true;
        }
        ChunkGroup group = groupedChunks.get(pos);
        PermissionMatrix perms = getPermissionMatrix(owner, group);
        int type = getRelationship(owner, player, group);
        return perms.canTrampleCrops(type);
    }

    /**
     * Obtains the effective permission matrix for the given chunk group. If the chunk group is null, then
     * the permission matrix of the owner is returned. If the owner does not have a permission matrix assigned
     * as of yet, then the default permission matrix will be assigned to the owner and the default permission matrix returned.
     * Like most other operations in this class, it is fully safe to use concurrently outside of the startup phase.
     *
     * @param owner The owner player. Used for fallback purposes
     * @param group The chunk group to use. Can be null.
     * @return The effective permission matrix
     */
    @NotNull
    public PermissionMatrix getPermissionMatrix(@NotNull UUID owner, @Nullable ChunkGroup group) {
        if (group == null) {
            PermissionMatrix perms = playerDefaults.get(owner);
            if (perms == null) {
                perms = PermissionMatrix.DEFAULT;
                PermissionMatrix retain = playerDefaults.putIfAbsent(owner, perms);
                if (retain != null) { // Race condition
                    perms = retain;
                }
            }
            return perms;
        } else {
            return group.permissions();
        }
    }

    private int getRelationship(@NotNull UUID owner, @NotNull UUID player, @Nullable ChunkGroup group) {
        if (owner.equals(player)) {
            return PermissionMatrix.PERSON_OWNER;
        }
        int type;
        if (group == null) {
            if (isTrusted(owner, player)) {
                type = PermissionMatrix.PERSON_TRUSTED;
            } else {
                type = PermissionMatrix.PERSON_STRANGER;
            }
        } else {
            // TODO per-group trusts
            if (isTrusted(owner, player)) {
                type = PermissionMatrix.PERSON_TRUSTED;
            } else {
                type = PermissionMatrix.PERSON_STRANGER;
            }
        }
        return type;
    }

    /**
     * Checks whether the player "trusted" is within the list of trusted players of the player "truster".
     * This method is fully safe to use in a concurrent environment, provided it isn't within the loading phase.
     *
     * @param truster The trusting player
     * @param trusted The player that should be checked whether it is in the trusted list
     * @return True if the trusted player is within the list of trusted players of the truster. See {@link Set#contains(Object)}.
     */
    public boolean isTrusted(@NotNull UUID truster, @NotNull UUID trusted) {
        Set<UUID> trustedPlayers = this.trustedPlayers.get(truster);
        if (trustedPlayers == null) {
            trustedPlayers = ConcurrentHashMap.newKeySet();
            Set<UUID> var10001 = this.trustedPlayers.putIfAbsent(trusted, trustedPlayers);
            if (var10001 != null) { // Race condition
                trustedPlayers = var10001;
            }
        }
        return trustedPlayers.contains(trusted);
    }

    private boolean readElementStartByte(@NotNull InputStream input) throws IOException {
        int read = input.read();
        if (read == 0) {
            return false;
        }
        if (read != 1) {
            throw new IOException("Encountered non-binary element start byte. Expected 0 or 1 but got " + read);
        }
        return true;
    }

    protected void load(@NotNull DataInputStream in, short version) throws IOException {
        groupedChunks.clear();
        groupNames.clear();
        playerDefaults.clear();
        trustedPlayers.clear();
        playerGroups.clear();

        while(readElementStartByte(in)) {
            UUID ownerId = new UUID(in.readLong(), in.readLong());
            String groupName = in.readUTF();
            PermissionMatrix perms = PermissionMatrix.deserialize(in, version);
            if (groupName == null) {
                throw new IOException(groupName);
            }
            Collection<WorldPosition> positions = new HashSet<>();
            ChunkGroup cgroup = new ChunkGroup(groupName, ownerId, new AtomicReference<>(perms), positions);
            groupNames.put(new PlayerAttachedString(ownerId, groupName), cgroup);

            Set<ChunkGroup> groups = playerGroups.get(ownerId);
            if (groups == null) {
                groups = ConcurrentHashMap.newKeySet();
                if (!Objects.isNull(playerGroups.put(ownerId, groups))) {
                    throw new ConcurrentModificationException("Error L340. Make sure no plugin is accessing the chunk group manager during the load phase.");
                }
            }
            groups.add(cgroup);

            while(readElementStartByte(in)) {
                WorldPosition pos = new WorldPosition(new UUID(in.readLong(), in.readLong()), in.readLong());
                positions.add(pos);
                groupedChunks.put(pos, cgroup);
            }
        }

        while (readElementStartByte(in)) {
            UUID player = new UUID(in.readLong(), in.readLong());
            PermissionMatrix perms = PermissionMatrix.deserialize(in, version);
            playerDefaults.put(player, perms);
        }

        while (readElementStartByte(in)) {
            UUID truster = new UUID(in.readLong(), in.readLong());
            Set<UUID> trusted = ConcurrentHashMap.newKeySet();
            while (readElementStartByte(in)) {
                trusted.add(new UUID(in.readLong(), in.readLong()));
            }
            if (trusted.isEmpty()) {
                continue; // Slowly purge out useless keys
            }
            trustedPlayers.put(truster, trusted);
        }
    }

    public void loadSafely(@NotNull InputStream in) throws IOException {
        short version = Shorts.fromBytes((byte) in.read(), (byte) in.read());
        if (version != 0 && version != 1) {
            throw new IOException("Invalid version. Expected 0 or 1, got " + version);
        }
        long shouldBeChecksum = Longs.fromByteArray(in.readNBytes(8));
        CheckedInputStream cin = new CheckedInputStream(in, new Adler32());
        load(new DataInputStream(cin), version);
        if (cin.getChecksum().getValue() != shouldBeChecksum) {
            throw new IOException("Expected checksum and actual checksum do not match.");
        }
    }

    /**
     * Removes the player "trusted" from the list of trusted players of the player "truster".
     * This method is fully safe to use in a concurrent environment, provided it isn't within the loading phase.
     *
     * @param truster The trusting player
     * @param trusted The player that should be removed from the trust list of the trusting player
     * @return True if the trusting player trusted the trusted player. See {@link Set#remove(Object)}.
     */
    public boolean removeTrustedPlayer(@NotNull UUID truster, @NotNull UUID trusted) {
        Set<UUID> trustedPlayers = this.trustedPlayers.get(truster);
        if (trustedPlayers == null) {
            trustedPlayers = ConcurrentHashMap.newKeySet();
            Set<UUID> var10001 = this.trustedPlayers.putIfAbsent(trusted, trustedPlayers);
            if (var10001 != null) { // Race condition
                trustedPlayers = var10001;
            }
        }
        return trustedPlayers.remove(trusted);
    }

    protected void save(@NotNull DataOutputStream out, short version) throws IOException {
        for (ChunkGroup cgroup : groupNames.values()) {
            out.write(1);
            out.writeLong(cgroup.owner().getMostSignificantBits());
            out.writeLong(cgroup.owner().getLeastSignificantBits());
            out.writeUTF(cgroup.name());
            cgroup.permissions().serialize(out, version);
            for (WorldPosition pos : cgroup.claimedChunks()) {
                out.write(1);
                out.writeLong(pos.world().getMostSignificantBits());
                out.writeLong(pos.world().getLeastSignificantBits());
                out.writeLong(pos.chunkPos());
            }
            out.write(0);
        }
        out.write(0);

        for (Map.Entry<UUID, PermissionMatrix> e : playerDefaults.entrySet()) {
            out.write(1);
            out.writeLong(e.getKey().getMostSignificantBits());
            out.writeLong(e.getKey().getLeastSignificantBits());
            e.getValue().serialize(out, version);
        }
        out.write(0);

        for (Map.Entry<UUID, Set<UUID>> e : trustedPlayers.entrySet()) {
            out.write(1);
            out.writeLong(e.getKey().getMostSignificantBits());
            out.writeLong(e.getKey().getLeastSignificantBits());
            for (UUID id : e.getValue()) {
                out.write(1);
                out.writeLong(id.getMostSignificantBits());
                out.writeLong(id.getLeastSignificantBits());
            }
            out.write(0);
        }
        out.write(0);
    }

    public void saveSafely(@NotNull OutputStream out) throws IOException {
        ByteArrayOutputStream tout = new ByteArrayOutputStream();
        CheckedOutputStream cout = new CheckedOutputStream(tout, new Adler32());
        save(new DataOutputStream(cout), CURRENT_VERSION);
        out.write(Shorts.toByteArray(CURRENT_VERSION));
        out.write(Longs.toByteArray(cout.getChecksum().getValue()));
        out.write(tout.toByteArray());
        out.flush();
    }

    /**
     * Set the default permission of a player. These permission are used for every claim of the player that is not within a
     * chunk group. This method is guaranteed to be safe to use in concurrent environments however it may ignore the other
     * permissions that are set. After lengthy evaluations I came to the conclusion that this is the best compromise we can
     * do.
     *
     * @param player The player to apply the permissions for
     * @param perms The new permissions
     */
    public void setPlayerDefaultPermissions(@NotNull UUID player, @NotNull PermissionMatrix perms) {
        playerDefaults.put(player, perms);
    }

    /**
     * Obtains the {@link ChunkGroup ChunkGruops} owned by the given player. It may return null if the player
     * does not own any chunk groups. Furthermore the returned instance is immutable so the developer does not cause
     * mild temporary damage. Under rare circumstances the returned set can be empty, however after a restart
     * it is likely to go null.
     *
     * @param player The player to get the owned groups from
     * @return A set of the chunk groups owned by the player, or null if the player does not own any groups.
     */
    @Nullable
    public Set<ChunkGroup> getOwnedGroups(@NotNull UUID player) {
        return this.playerGroups.get(player);
    }

    /**
     * Creates an empty chunk group with the specified name. If there is already a chunk
     * group attached to the same name-player pair, then this method will throw a {@link ElementAlreadyExistsException}.
     * This method is safe to call in a concurrent environment.
     *
     * @param player The owner of the returned chunk group
     * @param name The name of the chunk group
     * @return The newly created instance
     * @throws ElementAlreadyExistsException If there is already a chunk owned by the player with the specified name
     */
    @NotNull
    public ChunkGroup createChunkGroup(@NotNull UUID player, @NotNull String name) throws ElementAlreadyExistsException {
        PermissionMatrix perms = PermissionMatrix.DEFAULT;
        Set<WorldPosition> positions = ConcurrentHashMap.newKeySet();
        if (positions == null) {
            throw new NullPointerException();
        }
        ChunkGroup group = new ChunkGroup(name, player, new AtomicReference<>(perms), positions);
        if (groupNames.putIfAbsent(new PlayerAttachedString(player, name), group) != null) {
            throw new ElementAlreadyExistsException("There is already a chunk group with the given owner and name.");
        }
        Set<ChunkGroup> playerGroups = this.playerGroups.get(player);
        if (playerGroups == null) {
            playerGroups = ConcurrentHashMap.newKeySet();
            Set<ChunkGroup> retained = this.playerGroups.putIfAbsent(player, playerGroups);
            if (retained != null) {
                playerGroups = retained;
            }
        }
        playerGroups.add(group);
        return group;
    }

    @Nullable
    public ChunkGroup getGroupAt(@NotNull WorldPosition position) {
        return groupedChunks.get(position);
    }

    @Nullable
    public ChunkGroup getChunkGroup(@NotNull UUID player, @NotNull String name) {
        return groupNames.get(new PlayerAttachedString(player, name));
    }

    /**
     * Assigns a chunk to a given group. Like many other operations within this class, this method is perfectly
     * safe to invoke in a concurrent environment.
     * If this operation is performed twice in a row with no other operations happening between that, then it is guaranteed
     * to return true twice or false twice.
     *
     * @param group The group to assign the chunk to
     * @param position A reference to the chunk that is assigned
     * @return True if the chunk position was added to the chunk group without problems. False if it was not performed (e.g. already assigned to a different group)
     */
    public boolean addChunk(@NotNull ChunkGroup group, @NotNull WorldPosition position) {
        ChunkGroup old = groupedChunks.putIfAbsent(position, group);
        if (old == group) {
            return true;
        }
        if (old == null) {
            boolean ch = group.claimedChunks().add(position);
            if (!ch) {
                System.err.println("Error code L538. Please report this issue to the maintainers of Presence.");
            }
            return true;
        }
        return false;
    }

    /**
     * Removes a chunk from the given group. Like many other operations within this class, this method is perfectly
     * safe to invoke in a concurrent environment.
     * If this operation is performed twice in a row with no other operations happening between that, then it is guaranteed
     * to return false the second time.
     * If the expected group does not match then false will be returned, so if this method returns false it does not mean
     * that it is neutral afterwards.
     *
     * @param group The group to assign the chunk to
     * @param position A reference to the chunk that is assigned
     * @return True if a change occurred, false otherwise
     */
    public boolean removeChunk(@NotNull ChunkGroup group, @NotNull WorldPosition position) {
        if (!group.claimedChunks().remove(position)) {
            return false;
        }
        if (!groupedChunks.remove(position, group)) {
            // Race condition. I am unsure how to solve this one
            System.err.println("Error code L563. Please report this issue to the maintainers of Presence.");
        }
        return true;
    }

    public void setChunk(@NotNull ChunkGroup group, @NotNull WorldPosition position) {
        ChunkGroup currentAssigned = groupedChunks.get(position);
        if (currentAssigned == group) {
            return;
        }
        while (!addChunk(group, position)) {
            currentAssigned = groupedChunks.get(position);
            if (currentAssigned == null) { // shouldn't happen
                continue;
            }
            while (!removeChunk(currentAssigned, position)) {
                currentAssigned = groupedChunks.get(position);
                if (currentAssigned == null) {
                    break;
                }
            }
        }
    }
}
