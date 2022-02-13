package de.geolykt.presence.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.opentest4j.AssertionFailedError;

import de.geolykt.presence.common.PresenceData;
import de.geolykt.presence.common.util.PlayerAttachedScore;

public class TestTicking {

    private static final int TEST_OWNER_SET_ITERATIONS = 10;

    @Test
    public void testOwnerSet() {
        PresenceData presence = new PresenceData(0.0D);
        UUID tickingPlayer = UUID.randomUUID();
        UUID world = UUID.randomUUID();
        assertNotNull(world);
        assertNotNull(tickingPlayer);

        for (int i = 0; i < TEST_OWNER_SET_ITERATIONS; i++) {
            presence.tick(tickingPlayer, world, i, i);
        }
        for (int i = 0; i < TEST_OWNER_SET_ITERATIONS; i++) {
            PlayerAttachedScore score = presence.getOwner(world, i, i);
            assertNotNull(score);
            assertEquals(1, score.score().get());
            assertEquals(tickingPlayer, score.getPlayer());
            assertEquals(tickingPlayer, score.player());
        }
    }

    @Test
    public void testOwnerChange() {
        PresenceData presence = new PresenceData(0.0D);
        UUID tickingPlayer1 = UUID.randomUUID();
        UUID tickingPlayer2 = UUID.randomUUID();
        UUID world = UUID.randomUUID();
        assertNotNull(world);
        while (tickingPlayer1.equals(tickingPlayer2)) {
            tickingPlayer1 = UUID.randomUUID(); // Never let chance dictate anything
        }
        assertNotNull(tickingPlayer2);

        for (int i = 0; i < 1_000; i++) {
            presence.tick(tickingPlayer1, world, i, i);
            presence.tick(tickingPlayer2, world, i, i);
            presence.tick(tickingPlayer2, world, i, i);
        }
        for (int i = 0; i < 1_000; i++) {
            PlayerAttachedScore owner = presence.getOwner(world, i, i);
            assertNotNull(owner);
            assertEquals(2, owner.score().get());
            assertEquals(tickingPlayer2, owner.getPlayer());
            PlayerAttachedScore successor = presence.getSuccessor(world, i, i);
            assertNotNull(successor);
            assertEquals(1, successor.score().get());
            assertEquals(tickingPlayer1, successor.getPlayer());
        }
    }

    @Test
    public void testTickNearbyChance() {
        assertThrows(RuntimeException.class, () -> {
            new PresenceData(1.0);
        });
        assertTimeoutPreemptively(Duration.of(3, ChronoUnit.SECONDS), () -> {
            PresenceData pd = new PresenceData(0.6);
            UUID tickingPlayer = UUID.randomUUID();
            UUID world = UUID.randomUUID();
            assertNotNull(world);
            assertNotNull(tickingPlayer);
            for (int i = 0; i < 100; i++) {
                pd.tick(tickingPlayer, world, i, i);
            }
        });
        PresenceData presence = new PresenceData(0.99);
        UUID tickingPlayer = UUID.randomUUID();
        UUID world = UUID.randomUUID();
        assertNotNull(world);
        assertNotNull(tickingPlayer);
        for (int i = 0; i < 1000; i++) {
            presence.tick(tickingPlayer, world, 0, 0);
        }
        int presenceCount = presence.getPresence(tickingPlayer, world, 0, 0);
        assertNotEquals(1000, presenceCount);
        assertEquals(presenceCount, presence.getPresence(tickingPlayer, world, 0, 0));
        if (presenceCount < 1000) {
            throw new AssertionFailedError("The presence count must be below 1000, but was " + presenceCount, null, presenceCount);
        }
    }
}
