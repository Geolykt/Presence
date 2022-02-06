package de.geolykt.presence.common;

import org.jetbrains.annotations.NotNull;

public class DataSource {

    private static PresenceData data;
    private static Configuration cfg;

    public static PresenceData getData() {
        return data;
    }

    public static Configuration getConfiguration() {
        return cfg;
    }

    public static void setData(@NotNull PresenceData data) {
        DataSource.data = data;
    }

    public static void setConfiguration(@NotNull Configuration config) {
        cfg = config;
    }
}
