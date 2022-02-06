package de.geolykt.presence.common;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
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

public class ChunkGroupManager {

    private final Map<WorldPosition, ChunkGroup> groupedChunks = new ConcurrentHashMap<>();
    private final Map<String, ChunkGroup> groupNames = new ConcurrentHashMap<>();
    private final Map<UUID, PermissionMatrix> playerDefaults = new ConcurrentHashMap<>();
    private final Map<UUID, Set<UUID>> trustedPlayers = new ConcurrentHashMap<>();

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

    protected void load(@NotNull DataInputStream in) throws IOException {
        groupedChunks.clear();
        groupNames.clear();

        for (int var10001 = in.readInt(); var10001 != 0; var10001--) {
            UUID ownerId = new UUID(in.readLong(), in.readLong());
            String groupName = in.readUTF();
            PermissionMatrix perms = PermissionMatrix.deserialize(in);
            if (groupName == null) {
                throw new IOException(groupName);
            }
            Collection<WorldPosition> positions = new HashSet<>();
            ChunkGroup cgroup = new ChunkGroup(groupName, ownerId, new AtomicReference<>(perms), positions);
            groupNames.put(groupName, cgroup);
            for (int var10002 = in.readInt(); var10002 != 0; var10002--) {
                WorldPosition pos = new WorldPosition(new UUID(in.readLong(), in.readLong()), in.readLong());
                positions.add(pos);
                groupedChunks.put(pos, cgroup);
            }
        }
    }

    public void loadSafely(@NotNull InputStream in) throws IOException {
        if (in.read() != 0) {
            throw new IOException("Invalid version. Expected 0");
        }
        long shouldBeChecksum = Longs.fromByteArray(in.readNBytes(4));
        CheckedInputStream cin = new CheckedInputStream(in, new Adler32());
        load(new DataInputStream(cin));
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

    protected void save(@NotNull DataOutputStream out) throws IOException {
        Collection<ChunkGroup> groups = groupNames.values();
        out.writeInt(groups.size());
        for (ChunkGroup cgroup : groups) {
            out.writeLong(cgroup.owner().getMostSignificantBits());
            out.writeLong(cgroup.owner().getLeastSignificantBits());
            out.writeUTF(cgroup.name());
            cgroup.permissions().serialize(out);
            Collection<WorldPosition> chunks = (Collection<WorldPosition>) cgroup.claimedChunks();
            out.writeInt(chunks.size());
            for (WorldPosition pos : chunks) {
                out.writeLong(pos.world().getMostSignificantBits());
                out.writeLong(pos.world().getLeastSignificantBits());
                out.writeLong(pos.chunkPos());
            }
        }
    }

    public void saveSafely(@NotNull OutputStream out) throws IOException {
        ByteArrayOutputStream tout = new ByteArrayOutputStream();
        CheckedOutputStream cout = new CheckedOutputStream(out, new Adler32());
        save(new DataOutputStream(cout));
        out.write(0);
        out.write(Longs.toByteArray(cout.getChecksum().getValue()));
        out.write(tout.toByteArray());
    }
}
