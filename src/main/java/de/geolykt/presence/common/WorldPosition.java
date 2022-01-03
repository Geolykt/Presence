package de.geolykt.presence.common;

import java.util.UUID;

public record WorldPosition(UUID world, long chunkPos) { }
