package haaa.shitbot.core.config;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Selected image theme with field-by-field fallback to the bundled default.
 * Template files are deliberately data-only: renderers keep control of the
 * drawing algorithm while administrators can change its layout and palette.
 */
public final class ImageTemplate {
    public static final String DEFAULT_TEMPLATE = "default";
    private static final String MISSING = "\u0000__missing__\u0000";

    private final String name;
    private final ConfigSource selected;
    private final ConfigSource fallback;

    public ImageTemplate(String name, ConfigSource selected, ConfigSource fallback) {
        this.name = normalizeName(name);
        if (selected == null) {
            throw new IllegalArgumentException("selected image template cannot be null");
        }
        if (fallback == null) {
            throw new IllegalArgumentException("fallback image template cannot be null");
        }
        this.selected = selected;
        this.fallback = fallback;
    }

    public String getName() {
        return name;
    }

    public String getString(String path, String fallbackValue) {
        String value = selected.getString(path, MISSING);
        if (!MISSING.equals(value)) {
            return value == null ? fallbackValue : value;
        }
        value = fallback.getString(path, MISSING);
        return MISSING.equals(value) || value == null ? fallbackValue : value;
    }

    public int getInt(String path, int minimum, int maximum, int fallbackValue) {
        int sentinel = Integer.MIN_VALUE;
        int value = selected.getInt(path, sentinel);
        if (value == sentinel) {
            value = fallback.getInt(path, fallbackValue);
        }
        if (value < minimum) {
            return minimum;
        }
        return Math.min(value, maximum);
    }

    public Color getColor(String path, String fallbackValue) {
        String configured = getString(path, fallbackValue);
        Color parsed = parseColor(configured);
        if (parsed != null) {
            return parsed;
        }
        Color fallbackColor = parseColor(fallbackValue);
        return fallbackColor == null ? Color.WHITE : fallbackColor;
    }

    public List<Color> getColors(String path, String... fallbackValues) {
        List<String> values = selected.getStringList(path);
        if (values == null || values.isEmpty()) {
            values = fallback.getStringList(path);
        }
        List<Color> colors = parseColors(values);
        if (!colors.isEmpty()) {
            return colors;
        }
        List<String> defaults = new ArrayList<String>();
        if (fallbackValues != null) {
            Collections.addAll(defaults, fallbackValues);
        }
        return parseColors(defaults);
    }

    public static String normalizeName(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.toLowerCase(Locale.ROOT).endsWith(".yml")) {
            normalized = normalized.substring(0, normalized.length() - 4);
        }
        if (normalized.isEmpty()) {
            return DEFAULT_TEMPLATE;
        }
        if (!normalized.matches("[A-Za-z0-9_-]+")) {
            throw new IllegalArgumentException(
                    "Image template name may only contain letters, numbers, underscores and hyphens: " + value);
        }
        return normalized;
    }

    public static String resourcePath(String name) {
        return "templates/" + normalizeName(name) + ".yml";
    }

    private static Color parseColor(String value) {
        if (value == null) {
            return null;
        }
        String hex = value.trim();
        if (hex.startsWith("#")) {
            hex = hex.substring(1);
        }
        try {
            if (hex.length() == 6) {
                return new Color(Integer.parseInt(hex, 16));
            }
            if (hex.length() == 8) {
                long argb = Long.parseLong(hex, 16);
                return new Color((int) argb, true);
            }
        } catch (NumberFormatException ignored) {
            return null;
        }
        return null;
    }

    private static List<Color> parseColors(List<String> values) {
        if (values == null || values.isEmpty()) {
            return Collections.emptyList();
        }
        List<Color> colors = new ArrayList<Color>(values.size());
        for (String value : values) {
            Color color = parseColor(value);
            if (color != null) {
                colors.add(color);
            }
        }
        return Collections.unmodifiableList(colors);
    }
}
