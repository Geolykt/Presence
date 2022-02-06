package de.geolykt.presence.common;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

public final class PermissionMatrix {

    // TODO auto-re-plant permission or something
    // TODO fair pvp is an issue here ...

    public static final byte PERSON_OWNER    = 0b00_00_00_01;
    public static final byte PERSON_STRANGER = 0b00_00_00_10;
    public static final byte PERSON_TRUSTED  = 0b00_00_01_00;

    @NotNull
    public static final PermissionMatrix DEFAULT = new PermissionMatrix(
            PERSON_OWNER | PERSON_TRUSTED,
            PERSON_OWNER | PERSON_TRUSTED,
            PERSON_OWNER | PERSON_TRUSTED,
            PERSON_OWNER | PERSON_TRUSTED,
            PERSON_OWNER | PERSON_TRUSTED,
            PERSON_OWNER | PERSON_TRUSTED,
            PERSON_OWNER | PERSON_TRUSTED,
            0);

    @NotNull
    public static final PermissionMatrix deserialize(@NotNull InputStream in) throws IOException {
        return new PermissionMatrix(in.read(), in.read(), in.read(), in.read(), in.read(), in.read(), in.read(), in.read());
    }

    private final byte attack;
    private final byte attackNamed;
    private final byte build;
    private final byte destroy;
    private final byte harvestCrops;
    private final byte interact;
    private final byte interactEntity;
    private final byte trample;

    public PermissionMatrix(byte attack, byte attackNamed, byte build, byte destroy, byte harvestCrops,
            byte interact, byte interactEntity, byte trample) {
        this.attack = attack;
        this.attackNamed = attackNamed;
        this.build = build;
        this.destroy = destroy;
        this.harvestCrops = harvestCrops;
        this.interact = interact;
        this.interactEntity = interactEntity;
        this.trample = trample;
    }

    public PermissionMatrix(int attack, int attackNamed, int build, int destroy, int harvestCrops,
            int interact, int interactEntity, int trample) {
        this((byte) attack, (byte) attackNamed, (byte) build, (byte) destroy, (byte) harvestCrops,
                (byte) interact, (byte) interactEntity, (byte) trample);
    }

    /**
     * Changes the attack permission for the given person. If "allow" is true then the permission is set,
     * if "allow" is false the permission is removed. The current permission matrix is immutable and thus does
     * not change with this call
     *
     * @param person The person to apply the permission for
     * @param allow True to allow, False to deny
     * @return The new effective permissions.
     */
    @NotNull
    @Contract(value = "_, _ -> new", pure = true)
    public final PermissionMatrix alterAttack(final int person, final boolean allow) {
        byte attack = this.attack;
        if (allow) {
            attack |= person;
        } else {
            attack &= ~person;
        }
        return new PermissionMatrix(attack, attackNamed, build, destroy, harvestCrops, interact, interactEntity, trample);
    }

    /**
     * Changes the attackNamed permission for the given person. If "allow" is true then the permission is set,
     * if "allow" is false the permission is removed. The current permission matrix is immutable and thus does
     * not change with this call
     *
     * @param person The person to apply the permission for
     * @param allow True to allow, False to deny
     * @return The new effective permissions.
     */
    @NotNull
    @Contract(value = "_, _ -> new", pure = true)
    public final PermissionMatrix alterAttackNamed(final int person, final boolean allow) {
        byte attackNamed = this.attackNamed;
        if (allow) {
            attackNamed |= person;
        } else {
            attackNamed &= ~person;
        }
        return new PermissionMatrix(attack, attackNamed, build, destroy, harvestCrops, interact, interactEntity, trample);
    }

    /**
     * Changes the build permission for the given person. If "allow" is true then the permission is set,
     * if "allow" is false the permission is removed. The current permission matrix is immutable and thus does
     * not change with this call
     *
     * @param person The person to apply the permission for
     * @param allow True to allow, False to deny
     * @return The new effective permissions.
     */
    @NotNull
    @Contract(value = "_, _ -> new", pure = true)
    public final PermissionMatrix alterBuild(final int person, final boolean allow) {
        byte build = this.build;
        if (allow) {
            build |= person;
        } else {
            build &= ~person;
        }
        return new PermissionMatrix(attack, attackNamed, build, destroy, harvestCrops, interact, interactEntity, trample);
    }

    /**
     * Changes the destroy permission for the given person. If "allow" is true then the permission is set,
     * if "allow" is false the permission is removed. The current permission matrix is immutable and thus does
     * not change with this call
     *
     * @param person The person to apply the permission for
     * @param allow True to allow, False to deny
     * @return The new effective permissions.
     */
    @NotNull
    @Contract(value = "_, _ -> new", pure = true)
    public final PermissionMatrix alterDestroy(final int person, final boolean allow) {
        byte destroy = this.destroy;
        if (allow) {
            destroy |= person;
        } else {
            destroy &= ~person;
        }
        return new PermissionMatrix(attack, attackNamed, build, destroy, harvestCrops, interact, interactEntity, trample);
    }

    /**
     * Changes the harvestCrops permission for the given person. If "allow" is true then the permission is set,
     * if "allow" is false the permission is removed. The current permission matrix is immutable and thus does
     * not change with this call
     *
     * @param person The person to apply the permission for
     * @param allow True to allow, False to deny
     * @return The new effective permissions.
     */
    @NotNull
    @Contract(value = "_, _ -> new", pure = true)
    public final PermissionMatrix alterHarvestCrops(final int person, final boolean allow) {
        byte harvestCrops = this.destroy;
        if (allow) {
            harvestCrops |= person;
        } else {
            harvestCrops &= ~person;
        }
        return new PermissionMatrix(attack, attackNamed, build, destroy, harvestCrops, interact, interactEntity, trample);
    }

    /**
     * Changes the interact permission for the given person. If "allow" is true then the permission is set,
     * if "allow" is false the permission is removed. The current permission matrix is immutable and thus does
     * not change with this call
     *
     * @param person The person to apply the permission for
     * @param allow True to allow, False to deny
     * @return The new effective permissions.
     */
    @NotNull
    @Contract(value = "_, _ -> new", pure = true)
    public final PermissionMatrix alterInteract(final int person, final boolean allow) {
        byte interact = this.interact;
        if (allow) {
            interact |= person;
        } else {
            interact &= ~person;
        }
        return new PermissionMatrix(attack, attackNamed, build, destroy, harvestCrops, interact, interactEntity, trample);
    }

    /**
     * Changes the interactEntity permission for the given person. If "allow" is true then the permission is set,
     * if "allow" is false the permission is removed. The current permission matrix is immutable and thus does
     * not change with this call
     *
     * @param person The person to apply the permission for
     * @param allow True to allow, False to deny
     * @return The new effective permissions.
     */
    @NotNull
    @Contract(value = "_, _ -> new", pure = true)
    public final PermissionMatrix alterInteractEntity(final int person, final boolean allow) {
        byte interactEntity = this.interactEntity;
        if (allow) {
            interactEntity |= person;
        } else {
            interactEntity &= ~person;
        }
        return new PermissionMatrix(attack, attackNamed, build, destroy, harvestCrops, interact, interactEntity, trample);
    }

    /**
     * Changes the trample permission for the given person. If "allow" is true then the permission is set,
     * if "allow" is false the permission is removed. The current permission matrix is immutable and thus does
     * not change with this call
     *
     * @param person The person to apply the permission for
     * @param allow True to allow, False to deny
     * @return The new effective permissions.
     */
    @NotNull
    @Contract(value = "_, _ -> new", pure = true)
    public final PermissionMatrix alterTrample(final int person, final boolean allow) {
        byte trample = this.trample;
        if (allow) {
            trample |= person;
        } else {
            trample &= ~person;
        }
        return new PermissionMatrix(attack, attackNamed, build, destroy, harvestCrops, interact, interactEntity, trample);
    }

    @Contract(pure = true)
    public final boolean canAttack(final int person) {
        return (this.attack & person) != 0;
    }

    @Contract(pure = true)
    public final boolean canAttackNamedEntities(final int person) {
        return (this.attackNamed & person) != 0;
    }

    @Contract(pure = true)
    public final boolean canBuild(final int person) {
        return (this.build & person) != 0;
    }


    @Contract(pure = true)
    public final boolean canDestroy(final int person) {
        return (this.destroy & person) != 0;
    }

    @Contract(pure = true)
    public final boolean canHarvestCrops(final int person) {
        return (this.harvestCrops & person) != 0;
    }

    @Contract(pure = true)
    public final boolean canInteract(final int person) {
        return (this.interact & person) != 0;
    }

    @Contract(pure = true)
    public final boolean canInteractWithEntity(final int person) {
        return (this.interactEntity & person) != 0;
    }

    @Contract(pure = true)
    public final boolean canTrampleCrops(final int person) {
        return (this.trample & person) != 0;
    }

    @Contract(pure = true)
    public final byte getAttackBitfield() {
        return attack;
    }

    @Contract(pure = true)
    public final byte getAttackNamedBitfield() {
        return attackNamed;
    }

    @Contract(pure = true)
    public final byte getBuildBitfield() {
        return build;
    }

    @Contract(pure = true)
    public final byte getDestroyBitfield() {
        return destroy;
    }

    @Contract(pure = true)
    public final byte getHarvestCropsBitfield() {
        return harvestCrops;
    }

    @Contract(pure = true)
    public final byte getInteractBlockBitfield() {
        return interact;
    }

    @Contract(pure = true)
    public final byte getInteractEntityBitfield() {
        return interactEntity;
    }

    @Contract(pure = true)
    public final byte getTrampleBitfield() {
        return trample;
    }

    public final void serialize(@NotNull OutputStream out) throws IOException {
        out.write(this.attack);
        out.write(this.attackNamed);
        out.write(this.build);
        out.write(this.destroy);
        out.write(this.harvestCrops);
        out.write(this.interact);
        out.write(this.interactEntity);
        out.write(this.trample);
    }
}
