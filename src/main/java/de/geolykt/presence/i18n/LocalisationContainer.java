package de.geolykt.presence.i18n;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.json.JSONArray;
import org.json.JSONObject;

public class LocalisationContainer {

    private Map<String, I18NLanguage> languages = new HashMap<>();

    public void load(File file) throws IOException {
        languages.clear();
        try (FileInputStream fis = new FileInputStream(file)) {
            JSONArray json = new JSONArray(new String(fis.readAllBytes(), StandardCharsets.UTF_8));
            for (Object o : json) {
                I18NLanguage lang = new I18NLanguage((JSONObject) o);
                languages.put(lang.getCountryCode(), lang);
            }
        }
    }

    @NotNull
    @Contract(pure = true)
    public String get(@NotNull I18NKey key, @NotNull Locale locale) {
        I18NLanguage lang = languages.get(locale.getISO3Language());
        if (lang == null) {
            lang = languages.get("eng");
        }
        return lang.get(key);
    }

    @SuppressWarnings("null")
    @NotNull
    @Contract(pure = true)
    public String get(@NotNull I18NKey key, @NotNull Locale locale, Object... placeholders) {
        return get(key, locale).formatted(placeholders);
    }
}
