package de.geolykt.presence.common;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.jetbrains.annotations.NotNull;

public record PlayerRecord(@NotNull UUID player, @NotNull AtomicInteger score) {

    @NotNull
    public UUID getPlayer() {
        return player;
    }
}
