package de.geolykt.presence.common;

import java.util.Collection;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.jetbrains.annotations.NotNull;

import de.geolykt.presence.common.util.WorldPosition;

public record ChunkGroup(@NotNull String name, @NotNull UUID owner, @NotNull AtomicReference<PermissionMatrix> permissionRef,
        @NotNull Collection<WorldPosition> claimedChunks) {

    @SuppressWarnings("null") // The reference should ideally not store null values
    @NotNull
    public PermissionMatrix permissions() {
        return permissionRef.get();
    }

    @Override
    public boolean equals(Object other) {
        if (other instanceof ChunkGroup otherGroup) {
            return this == other && otherGroup.name.equals(name) && otherGroup.owner.equals(owner);
        }
        return false;
    }

    @Override
    public int hashCode() {
        return name.hashCode() ^ owner.hashCode();
    }

    public @NotNull String getName() {
        return name;
    }

    public @NotNull UUID getOwner() {
        return owner;
    }
}
