package de.geolykt.presence.common.util;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

public record PlayerAttachedScore(@NotNull UUID player, @NotNull AtomicInteger score) {

    @NotNull
    public UUID getPlayer() {
        return player;
    }

    @Override
    public int hashCode() {
        return player.hashCode() ^ score.get() ^ 0x154646;
    }

    @Override
    @Contract(pure = true, value = "null -> false; !null -> _")
    public boolean equals(Object obj) {
        // Records automatically create hashCode/equals methods, however AtomicInteger does not implement a
        // decent hashCode/equals method, which is why we have to do it manually to not have a identity-like
        // equals implementation
        if (obj instanceof PlayerAttachedScore other) {
            return other.player.equals(this.player) && other.score.get() == this.score.get();
        }
        return false;
    }
}
