package de.geolykt.presence;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Tameable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.EntityBlockFormEvent;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.block.SpongeAbsorbEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.PlayerLeashEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerUnleashEntityEvent;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.metadata.MetadataValue;
import org.bukkit.projectiles.ProjectileSource;
import org.jetbrains.annotations.NotNull;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import de.geolykt.presence.common.Configuration;
import de.geolykt.presence.common.DataSource;
import de.geolykt.presence.common.PresenceData;
import de.geolykt.presence.i18n.I18NKey;

public class PresenceListener implements Listener {
    private final PresenceData data = DataSource.getData();
    private final Configuration presenceConfig = DataSource.getConfiguration();

    @NotNull
    private final Map<UUID, Long> lastComplainTime = new ConcurrentHashMap<>();

    @NotNull
    private final PresenceBukkit pl;

    public PresenceListener(@NotNull PresenceBukkit plugin) {
        this.pl = plugin;
    }

    private void noteCancelled(@NotNull Player player) {
        long time = System.currentTimeMillis();
        Long lastComplain = lastComplainTime.get(player.getUniqueId());
        if (lastComplain == null || time > (lastComplain + 10_000)) {
            lastComplainTime.put(player.getUniqueId(), time);
            player.sendMessage(Component.text(this.pl.getI18N().get(I18NKey.ACTION_NOT_PERMITTED, player.locale()), NamedTextColor.RED));
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent e) {
        Block block = e.getBlock();
        /* `>> 4` Has the same effect as `x / 16`; may god hail binary operators.
        *  Interestingly enough, this trick even works for negative values,
        *  which might be the case as `>>` is dependent on the sign extension
        */
        int chunkX = block.getX() >> 4;
        int chunkY = block.getZ() >> 4;
        if (presenceConfig.isHarvestableCrop(block.getType())) {
            if (!data.canHarvest(e.getPlayer().getUniqueId(), block.getWorld().getUID(), chunkX, chunkY)) {
                e.setCancelled(true);
                noteCancelled(e.getPlayer());
            }
        } else {
            if (!data.canBreak(e.getPlayer().getUniqueId(), block.getWorld().getUID(), chunkX, chunkY)) {
                e.setCancelled(true);
                noteCancelled(e.getPlayer());
            } else {
                block.removeMetadata("presence_spongeplacer", pl);
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBlockDamage(BlockDamageEvent e) {
        Block block = e.getBlock();
        int chunkX = block.getX() >> 4;
        int chunkY = block.getZ() >> 4;
        if (!data.canBreak(e.getPlayer().getUniqueId(), block.getWorld().getUID(), chunkX, chunkY)) {
            e.setCancelled(true);
            noteCancelled(e.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBlockInteract(PlayerInteractEvent e) {
        Block block = e.getClickedBlock();
        if (!e.hasBlock() || block == null) {
            return;
        }
        int chunkX = block.getX() >> 4;
        int chunkY = block.getZ() >> 4;
        if (e.getAction() == Action.PHYSICAL && block.getType() == Material.FARMLAND) {
            if (!data.canTrample(e.getPlayer().getUniqueId(), block.getWorld().getUID(), chunkX, chunkY)) {
                e.setCancelled(true);
                noteCancelled(e.getPlayer());
            }
        } else if (!data.canInteractWithBlock(e.getPlayer().getUniqueId(), block.getWorld().getUID(), chunkX, chunkY)) {
            e.setCancelled(true);
            noteCancelled(e.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent e) {
        Block placed = e.getBlockPlaced();
        int chunkX = placed.getX() >> 4;
        int chunkY = placed.getZ() >> 4; // This is something that I will get wrong one day
        if (!data.canBuild(e.getPlayer().getUniqueId(), placed.getWorld().getUID(), chunkX, chunkY)) {
            e.setCancelled(true);
            noteCancelled(e.getPlayer());
        } else if (placed.getType() == Material.SPONGE) {
            placed.setMetadata("presence_spongeplacer", new FixedMetadataValue(pl, e.getPlayer().getUniqueId()));
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onEntityHurt(EntityDamageByEntityEvent e) {

        if (e.getCause() == DamageCause.BLOCK_EXPLOSION || e.getCause() == DamageCause.ENTITY_EXPLOSION) {
            Location loc = e.getEntity().getLocation();
            boolean bool = !data.canExplode(e.getEntity().getWorld().getUID(), loc.getBlockX() >> 4, loc.getBlockZ() >> 4);
            if (bool) {
                e.setCancelled(true);
                return;
            }
        }

        if (e.getEntity() instanceof Monster mob && mob.getCustomName() == null) {
            return; // Hostile mobs can be attacked no matter what provided they do not have a nametag
        }

        Entity damager = e.getDamager();
        UUID damagerUUID;
        if (damager instanceof Projectile) {
            ProjectileSource src = ((Projectile) damager).getShooter();
            if (src instanceof Player) {
                damager = (Player) src;
                damagerUUID = ((Player) src).getUniqueId();
            } else {
                return;
            }
        } else if (damager instanceof Tameable tameable) {
            damagerUUID = tameable.getOwnerUniqueId();
            if (damagerUUID == null) {
                return;
            }
            damager = null;
        } else if (!(damager instanceof Player)){
            return;
        } else {
            damagerUUID = damager.getUniqueId();
        }

        Location loc = e.getEntity().getLocation();
        int chunkX = loc.getBlockX() >> 4;
        int chunkY = loc.getBlockZ() >> 4;

        if (e.getEntity().getCustomName() == null) {
            if (!data.canAttack(damagerUUID, loc.getWorld().getUID(), chunkX, chunkY)) {
                e.setCancelled(true);
                if (damager != null) {
                    noteCancelled((Player) damager);
                }
            }
        } else {
            if (!data.canAttackNamed(damagerUUID, loc.getWorld().getUID(), chunkX, chunkY)) {
                e.setCancelled(true);
                if (damager != null) {
                    noteCancelled((Player) damager);
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onEntityInteract(PlayerInteractEntityEvent e) {
        Location loc = e.getRightClicked().getLocation();
        int chunkX = loc.getBlockX() >> 4;
        int chunkY = loc.getBlockZ() >> 4;
        if (!data.canInteractWithEntities(e.getPlayer().getUniqueId(), loc.getWorld().getUID(), chunkX, chunkY)) {
            e.setCancelled(true);
            noteCancelled(e.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onEntityLeash(PlayerLeashEntityEvent e) {
        Location loc = e.getEntity().getLocation();
        int chunkX = loc.getBlockX() >> 4;
        int chunkY = loc.getBlockZ() >> 4;
        if (!data.canInteractWithEntities(e.getPlayer().getUniqueId(), loc.getWorld().getUID(), chunkX, chunkY)) {
            e.setCancelled(true);
            noteCancelled(e.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onEntityUnleash(PlayerUnleashEntityEvent e) {
        Location loc = e.getEntity().getLocation();
        int chunkX = loc.getBlockX() >> 4;
        int chunkY = loc.getBlockZ() >> 4;
        if (!data.canInteractWithEntities(e.getPlayer().getUniqueId(), loc.getWorld().getUID(), chunkX, chunkY)) {
            e.setCancelled(true);
            noteCancelled(e.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onExplosion(BlockExplodeEvent evt) {
        evt.blockList().removeIf(block -> {
            return !data.canExplode(block.getWorld().getUID(), block.getX() >> 4, block.getZ() >> 4);
        });
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onExplosion(EntityExplodeEvent evt) {
        evt.blockList().removeIf(block -> {
            return !data.canExplode(block.getWorld().getUID(), block.getX() >> 4, block.getZ() >> 4);
        });
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPlayerBlockForm(EntityBlockFormEvent evt) {
        Entity e = evt.getEntity();
        if (e instanceof Player) {
            Block block = evt.getBlock();
            int chunkX = block.getX() >> 4;
            int chunkY = block.getZ() >> 4;
            if (!data.canBuild(e.getUniqueId(), block.getWorld().getUID(), chunkX, chunkY)) {
                evt.setCancelled(true);
                noteCancelled((Player) e);
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onSignInteract(SignChangeEvent e) {
        Block block = e.getBlock();
        int chunkX = block.getX() >> 4;
        int chunkY = block.getZ() >> 4;
        if (!data.canInteractWithBlock(e.getPlayer().getUniqueId(), block.getWorld().getUID(), chunkX, chunkY)) {
            e.setCancelled(true);
            noteCancelled(e.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onSpongeAbsorb(SpongeAbsorbEvent evt) {
        Block sponge = evt.getBlock();
        List<MetadataValue> player = sponge.getMetadata("presence_spongeplacer");
        if (player.isEmpty()) {
            return;
        }
        UUID puid = (UUID) player.get(0).value();
        if (puid == null) {
            return;
        }
        Iterator<BlockState> iter = evt.getBlocks().iterator();
        while (iter.hasNext()) {
            BlockState block = iter.next();
            int chunkX = block.getX() >> 4;
            int chunkY = block.getZ() >> 4;
            if (!data.canBreak(puid, block.getWorld().getUID(), chunkX, chunkY)) {
                iter.remove();
            }
        }
    }
}
