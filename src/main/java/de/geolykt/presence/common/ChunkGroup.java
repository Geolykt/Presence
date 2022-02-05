package de.geolykt.presence.common;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.jetbrains.annotations.NotNull;

public record ChunkGroup(@NotNull String name, @NotNull UUID owner, @NotNull AtomicReference<PermissionMatrix> permissionRef,
        @NotNull Iterable<WorldPosition> claimedChunks) {

    @SuppressWarnings("null") // The reference should ideally not store 
    @NotNull
    public PermissionMatrix permissions() {
        return permissionRef.get();
    }
}
