package de.geolykt.presence;

import java.util.Locale;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import de.geolykt.presence.common.DataSource;
import de.geolykt.presence.common.PlayerRecord;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;

public class PresencePlaceholders extends PlaceholderExpansion {

    @NotNull
    private final PresenceBukkit plugin;

    public PresencePlaceholders(@NotNull PresenceBukkit pluginInstance) {
        plugin = pluginInstance;
    }

    @Override
    public boolean canRegister() {
        return true;
    }

    @Override
    @NotNull
    public String getAuthor() {
        return "Geolykt";
    }

    @Override
    public @NotNull String getIdentifier() {
        return "presence";
    }

    @Override
    public @NotNull String getVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public String onPlaceholderRequest(Player player, @NotNull String value) {
        if (player == null) {
            return "";
        }
        Location loc = player.getLocation();
        int chunkX = loc.getBlockX() >> 4;
        int chunkY = loc.getBlockZ() >> 4;
        UUID world = loc.getWorld().getUID();
        switch(value.toLowerCase(Locale.ROOT)) {
        case "claimowner": {
            PlayerRecord owner = DataSource.getData().getOwner(world, chunkX, chunkY);
            if (owner == null) {
                return "none";
            }
            UUID ownerUUID = owner.getPlayer();
            return Bukkit.getOfflinePlayer(ownerUUID).getName();
        }
        case "ownerpresence": {
            PlayerRecord owner = DataSource.getData().getOwner(world, chunkX, chunkY);
            if (owner == null) {
                return "0";
            }
            return owner.score().toString();
        }
        case "claimsuccessor": {
            PlayerRecord successor = DataSource.getData().getSuccessor(world, chunkX, chunkY);
            if (successor == null) {
                return "none";
            }
            UUID successorUUID = successor.getPlayer();
            return Bukkit.getOfflinePlayer(successorUUID).getName();
        }
        case "successorpresence": {
            PlayerRecord successor = DataSource.getData().getSuccessor(world, chunkX, chunkY);
            if (successor == null) {
                return "0";
            }
            return successor.score().toString();
        }
        case "playerpresence": {
            return Integer.toString(DataSource.getData().getPresence(player.getUniqueId(), world, chunkX, chunkY));
        }
        default:
            // Invalid placeholder
            return null;
        }
    }

    @Override
    public boolean persist() {
        return true;
    }
}
