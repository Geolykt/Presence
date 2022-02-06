package de.geolykt.presence.common.util;

import java.util.UUID;

import org.jetbrains.annotations.NotNull;

/**
 * A {@link String} that is loosely attached to a player via an {@link UUID}.
 */
public record PlayerAttachedString(@NotNull UUID player, @NotNull String string) {}
