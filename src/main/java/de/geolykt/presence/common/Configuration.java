package de.geolykt.presence.common;

public class Configuration {

    private final int autosaveInterval;
    private final int claimTickInterval;
    private final int claimTravelInterval;
    private final int scoreboardRefreshInterval;
    private final double tickNearbyChunksChance;
    private final boolean allowFlight;

    public Configuration(int sbRefresh, int tickInterval, int travelInterval,
            int autosave, int claimSizeInChunks, double recursiveTickChance,
            boolean flightInClaims) {
        scoreboardRefreshInterval = sbRefresh;
        claimTickInterval = tickInterval;
        claimTravelInterval = travelInterval;
        autosaveInterval = autosave;
        tickNearbyChunksChance = recursiveTickChance;
        allowFlight = flightInClaims;
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

    public boolean allowsFlight() {
        return allowFlight;
    }
}
