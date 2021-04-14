package de.geolykt.presence.common;

public class DataSource {

    private static PresenceData data;
    private static Configuration cfg;

    public static PresenceData getData() {
        return data;
    }

    public static Configuration getConfiguration() {
        return cfg;
    }

    public static void setData(PresenceData data) {
        DataSource.data = data;
    }

    public static void setConfiguration(Configuration config) {
        cfg = config;
    }
}
