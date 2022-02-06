package de.geolykt.presence.common;

import java.util.Set;

import org.bukkit.Material;
import org.jetbrains.annotations.NotNull;

public class Configuration {

    private final int autosaveInterval;
    private final int claimTickInterval;
    private final int claimTravelInterval;
    private final int scoreboardRefreshInterval;
    private final double tickNearbyChunksChance;
    private final boolean allowFlight;

    @NotNull
    private final Set<Material> harvestableCrops;

    public Configuration(int sbRefresh, int tickInterval, int travelInterval,
            int autosave, double recursiveTickChance,
            boolean flightInClaims, @NotNull Set<Material> harvestableCrops) {
        this.scoreboardRefreshInterval = sbRefresh;
        this.claimTickInterval = tickInterval;
        this.claimTravelInterval = travelInterval;
        this.autosaveInterval = autosave;
        this.tickNearbyChunksChance = recursiveTickChance;
        this.allowFlight = flightInClaims;
        this.harvestableCrops = harvestableCrops;
    }

    public boolean allowsFlight() {
        return allowFlight;
    }

    /**
     * The interval between the automatic saving process.
     *
     * @return The autosave interval in ticks.
     */
    public int getAutosaveInterval() {
        return autosaveInterval;
    }

    public int getClaimTickInterval() {
        return claimTickInterval;
    }

    /**
     * The interval between the checks of player travel in ticks.
     * This check is responsible for announcing the player when it
     * moved to a different claim.
     *
     * @return The interval in ticks
     */
    public int getClaimTravelInterval() {
        return claimTravelInterval;
    }

    public int getScoreboardRefreshInterval() {
        return scoreboardRefreshInterval;
    }

    public double getTickNearbyChunksChance() {
        return tickNearbyChunksChance;
    }

    /**
     * Checks whether a given material is an harvestable crop. This is used indirectly by
     * {@link PermissionMatrix#canHarvestCrops(int)} and other methods.
     * The server owner defines what a harvestable crops is via the configuration.
     *
     * @param mat The material to check
     * @return True if the material "mat" is considered to be a harvestable crop according to the configuration
     */
    public boolean isHarvestableCrop(@NotNull Material mat) {
        return this.harvestableCrops.contains(mat);
    }
}
