package de.geolykt.presence.i18n;

import java.util.EnumMap;
import java.util.Locale;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.json.JSONObject;

public class I18NLanguage {

    @NotNull
    private String cc;
    @NotNull
    private EnumMap<@NotNull I18NKey, String> translations = new EnumMap<>(I18NKey.class);

    public I18NLanguage(JSONObject json) {
        String s = json.getString("language");
        if (s != null) {
            cc = s;
        } else {
            throw new NullPointerException("L22");
        }
        JSONObject translations = json.getJSONObject("translations");
        for (String translationKey : translations.keySet()) {
            try {
                I18NKey key = I18NKey.valueOf(translationKey.toUpperCase(Locale.ROOT));
                this.translations.put(key, translations.getString(translationKey));
            } catch (Exception e) {
                try {
                    throw new IllegalStateException("Unknown key: " + translationKey + " for locale " + s, e);
                } catch (IllegalStateException e2) {
                    e2.printStackTrace();
                    continue;
                }
            }
        }
    }

    @NotNull
    @Contract(pure = true)
    public String getCountryCode() {
        return cc;
    }

    @SuppressWarnings("null")
    @NotNull
    @Contract(pure = true, value = "!null -> !null")
    public String get(@NotNull I18NKey key) {
        return translations.getOrDefault(key, key.name());
    }
}
