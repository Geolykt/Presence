package de.geolykt.presence;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import de.geolykt.presence.common.DataSource;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;

public class PresencePlaceholders extends PlaceholderExpansion {

    private final PresenceBukkit plugin;

    public PresencePlaceholders(PresenceBukkit pluginInstance) {
        if (pluginInstance == null) {
            throw new IllegalArgumentException("What's a null instance worth?");
        }
        plugin = pluginInstance;
    }

    @Override
    public boolean canRegister() {
        return true;
    }

    @Override
    public @NotNull String getAuthor() {
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
        switch(value.toLowerCase(Locale.ROOT)) {
        case "claimowner": {
            Map.Entry<UUID, Integer> owner = DataSource.getData().getOwner(chunkX, chunkY);
            if (owner == null) {
                return "none";
            }
            return Bukkit.getOfflinePlayer(owner.getKey()).getName();
        }
        case "ownerpresence": {
            Map.Entry<UUID, Integer> owner = DataSource.getData().getOwner(chunkX, chunkY);
            if (owner == null) {
                return "0";
            }
            return owner.getValue().toString();
        }
        case "claimsuccessor": {
            Map.Entry<UUID, Integer> successor = DataSource.getData().getSuccessor(chunkX, chunkY);
            if (successor == null) {
                return "none";
            }
            return Bukkit.getOfflinePlayer(successor.getKey()).getName();
        }
        case "successorpresence": {
            Map.Entry<UUID, Integer> successor = DataSource.getData().getSuccessor(chunkX, chunkY);
            if (successor == null) {
                return "0";
            }
            return successor.getValue().toString();
        }
        case "playerpresence": {
            return Integer.toString(DataSource.getData().getPresence(player.getUniqueId(), chunkX, chunkY));
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
