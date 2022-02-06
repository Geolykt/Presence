package de.geolykt.presence.common.util;

import java.util.UUID;

import org.jetbrains.annotations.NotNull;

/**
 * A record that is used to pair a chunk position to a player.
 */
public record PlayerAttachedPosition(@NotNull UUID player, @NotNull WorldPosition pos) {

    @Override
    public boolean equals(Object obj) {
        if (obj instanceof PlayerAttachedPosition other) {
            return other.player.equals(player) && other.pos.equals(pos);
        }
        return false;
    }

    @Override
    public int hashCode() {
        return player.hashCode() ^ pos.hashCode();
    }
}
