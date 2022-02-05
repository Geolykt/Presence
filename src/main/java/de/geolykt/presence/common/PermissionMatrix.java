package de.geolykt.presence.common;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import org.jetbrains.annotations.NotNull;

public final class PermissionMatrix {

    // TODO auto-re-plant permission or something
    // TODO fair pvp is an issue here ...

    public static final byte PERSON_OWNER    = 0b00_00_00_01;
    public static final byte PERSON_STRANGER = 0b00_00_00_10;
    public static final byte PERSON_TRUSTED  = 0b00_00_01_00;

    private final byte attack;
    private final byte attackNamed;
    private final byte build;
    private final byte destroy;
    private final byte harvestCrops;
    private final byte trample;

    @NotNull
    public static final PermissionMatrix DEFAULT = new PermissionMatrix(PERSON_OWNER | PERSON_TRUSTED,
            PERSON_OWNER | PERSON_TRUSTED,
            PERSON_OWNER | PERSON_TRUSTED,
            PERSON_OWNER | PERSON_TRUSTED,
            PERSON_OWNER | PERSON_TRUSTED, 0);

    public PermissionMatrix(byte attack, byte attackNamed, byte build, byte destroy, byte harvestCrops, byte trample) {
        this.attack = attack;
        this.attackNamed = attackNamed;
        this.build = build;
        this.destroy = destroy;
        this.harvestCrops = harvestCrops;
        this.trample = trample;
    }

    public PermissionMatrix(int attack, int attackNamed, int build, int destroy, int harvestCrops, int trample) {
        this((byte) attack, (byte) attackNamed, (byte) build, (byte) destroy, (byte) harvestCrops, (byte) trample);
    }

    public final boolean canAttack(final int person) {
        return (this.attack & person) != 0;
    }

    public final boolean canAttackNamedEntities(final int person) {
        return (this.attackNamed & person) != 0;
    }

    public final boolean canBuild(final int person) {
        return (this.build & person) != 0;
    }

    public final boolean canDestroy(final int person) {
        return (this.destroy & person) != 0;
    }

    public final boolean canHarvestCrops(final int person) {
        return (this.harvestCrops & person) != 0;
    }

    public final boolean canTrampleCrops(final int person) {
        return (this.trample & person) != 0;
    }

    public final byte getAttackBitfield() {
        return destroy;
    }

    public final byte getAttackNamedBitfield() {
        return destroy;
    }

    public final byte getBuildBitfield() {
        return build;
    }

    public final byte getDestroyBitfield() {
        return destroy;
    }

    public final byte getHarvestCropsBitfield() {
        return destroy;
    }

    public final byte getTrampleBitfield() {
        return trample;
    }

    @NotNull
    public static final PermissionMatrix deserialize(@NotNull InputStream in) throws IOException {
        return new PermissionMatrix(in.read(), in.read(), in.read(), in.read(), in.read(), in.read());
    }

    public final void serialize(@NotNull OutputStream out) throws IOException {
        out.write(this.attack);
        out.write(this.attackNamed);
        out.write(this.build);
        out.write(this.destroy);
        out.write(this.harvestCrops);
        out.write(this.trample);
    }
}
