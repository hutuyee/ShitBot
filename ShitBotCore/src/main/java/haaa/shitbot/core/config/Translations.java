package haaa.shitbot.core.config;

import haaa.shitbot.core.util.TextUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Immutable view of the selected language file with a complete fallback language.
 * Language files deliberately remain outside the JAR after first startup so server
 * administrators can copy, rename and translate them without changing code.
 */
public final class Translations {
    public static final String DEFAULT_LANGUAGE = "zh_CN";
    private static final String MISSING = "\u0000<missing>\u0000";

    private final String language;
    private final ConfigSource selected;
    private final ConfigSource fallback;

    public Translations(String language, ConfigSource selected, ConfigSource fallback) {
        this.language = normalizeLanguage(language);
        this.selected = require(selected, "selected");
        this.fallback = require(fallback, "fallback");
    }

    public String getLanguage() {
        return language;
    }

    public String get(String path) {
        return get(path, path);
    }

    public String get(String path, String finalFallback) {
        String value = selected.getString(path, MISSING);
        if (isPresent(value)) {
            return value;
        }
        value = fallback.getString(path, MISSING);
        return isPresent(value) ? value : finalFallback;
    }

    public List<String> getList(String path) {
        return getList(path, Collections.<String>emptyList());
    }

    public List<String> getList(String path, List<String> finalFallback) {
        List<String> values = clean(selected.getStringList(path));
        if (!values.isEmpty()) {
            return values;
        }
        values = clean(fallback.getStringList(path));
        if (!values.isEmpty()) {
            return values;
        }
        return clean(finalFallback);
    }

    public String format(String path, String... replacements) {
        String result = get(path);
        if (replacements == null) {
            return result;
        }
        for (int index = 0; index + 1 < replacements.length; index += 2) {
            result = TextUtil.replace(result, replacements[index], replacements[index + 1]);
        }
        return result;
    }

    public static String normalizeLanguage(String value) {
        String clean = value == null ? "" : value.trim();
        if (clean.toLowerCase(Locale.ROOT).endsWith(".yml")) {
            clean = clean.substring(0, clean.length() - 4);
        }
        if (clean.isEmpty()) {
            return DEFAULT_LANGUAGE;
        }
        if (!clean.matches("[A-Za-z0-9_-]+")) {
            throw new IllegalArgumentException("Language name may only contain letters, numbers, '_' and '-': " + clean);
        }
        return clean;
    }

    public static String resourcePath(String language) {
        return "lang/" + normalizeLanguage(language) + ".yml";
    }

    private static boolean isPresent(String value) {
        return value != null && !MISSING.equals(value);
    }

    private static List<String> clean(List<String> values) {
        if (values == null || values.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> result = new ArrayList<String>();
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                result.add(value.trim());
            }
        }
        return result.isEmpty()
                ? Collections.<String>emptyList()
                : Collections.unmodifiableList(result);
    }

    private static <T> T require(T value, String name) {
        if (value == null) {
            throw new IllegalArgumentException(name + " cannot be null");
        }
        return value;
    }
}
