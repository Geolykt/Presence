package de.geolykt.presence.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.UUID;
import java.util.zip.Adler32;
import java.util.zip.CheckedOutputStream;
import java.util.zip.Checksum;

import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import de.geolykt.presence.common.PresenceData;

import it.unimi.dsi.fastutil.io.FastByteArrayInputStream;
import it.unimi.dsi.fastutil.io.FastByteArrayOutputStream;

public class SerialisationTests {

    @Test
    public void testEmptySerialisation() {
        TestPresenceData data = new TestPresenceData();
        assertEquals(true, isRoundtripable(data));
    }

    // TODO this test is taking quite a long time, we might look into how we can optimise the calls to it
    @Test
    public void testTickedSerialisation() {
        TestPresenceData data = new TestPresenceData();

        UUID tickingPlayer1 = UUID.randomUUID();
        UUID tickingPlayer2 = UUID.randomUUID();
        UUID world = UUID.randomUUID();
        assertNotNull(world);
        while (tickingPlayer1.equals(tickingPlayer2)) {
            tickingPlayer1 = UUID.randomUUID(); // Never let chance dictate anything
        }
        assertNotNull(tickingPlayer2);

        for (int i = -10000; i < 100; i++) {
            data.tick(tickingPlayer1, world, i, i);
            data.tick(tickingPlayer2, world, i, i);
            data.tick(tickingPlayer2, world, i, i);
        }

        assertEquals(true, isRoundtripable(data));
    }

    private boolean isRoundtripable(TestPresenceData data) {
        TestPresenceData tpd = new TestPresenceData();
        tpd.loadFromArray(data.saveStateToArrayChecked());
        boolean ret = tpd.equals(data) && tpd.hasAuxiliaryEquality(data);
        return ret; // Separated to allow inspection with a debugger
    }

} class TestPresenceData extends PresenceData {

    public TestPresenceData() {
        super(0);
    }

    public byte[] saveStateToArrayChecked() {
        try (FastByteArrayOutputStream byteOut = new FastByteArrayOutputStream(0)) {
            Checksum checksum = new Adler32();
            CheckedOutputStream checkedOut = new CheckedOutputStream(byteOut, checksum);
            super.saveStateToStream(checkedOut);
            try (FastByteArrayOutputStream out = new FastByteArrayOutputStream(byteOut.length + 8)) {
                out.write(ByteBuffer.allocate(8).putLong(checksum.getValue()).array());
                out.write(byteOut.array, 0, (int) byteOut.position());
                return out.array;
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public void loadFromArray(byte[] object) {
        try (FastByteArrayInputStream in = new FastByteArrayInputStream(object)) {
            super.loadStateChecked(in);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    // Increase visibility of the method
    protected boolean hasAuxiliaryEquality(@NotNull PresenceData other) {
        return super.hasAuxiliaryEquality(other);
    }
}
