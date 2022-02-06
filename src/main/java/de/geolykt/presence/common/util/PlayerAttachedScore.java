package de.geolykt.presence.common.util;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.jetbrains.annotations.NotNull;

public record PlayerAttachedScore(@NotNull UUID player, @NotNull AtomicInteger score) {

    @NotNull
    public UUID getPlayer() {
        return player;
    }
}
