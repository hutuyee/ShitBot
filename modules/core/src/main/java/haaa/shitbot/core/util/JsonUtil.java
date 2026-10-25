package haaa.shitbot.core.util;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;

/**
 * Gson tree helpers restricted to APIs present in Minecraft 1.8.8's Gson 2.2.4.
 * Keeping the call surface here avoids linking newer convenience methods when a
 * server supplies an older Gson at runtime.
 */
public final class JsonUtil {
    private static final Gson GSON = new Gson();
    private static final Gson PRETTY_GSON = new GsonBuilder().setPrettyPrinting().create();

    private JsonUtil() {
    }

    public static JsonElement parse(String value) throws IOException {
        if (value == null) {
            throw new IOException("JSON input is missing");
        }
        try {
            return new JsonParser().parse(value);
        } catch (JsonParseException exception) {
            throw new IOException("Invalid JSON", exception);
        } catch (RuntimeException exception) {
            throw new IOException("Invalid JSON", exception);
        }
    }

    public static JsonElement parse(byte[] bytes) throws IOException {
        if (bytes == null) {
            throw new IOException("JSON input is missing");
        }
        try {
            return new JsonParser().parse(new String(bytes, StandardCharsets.UTF_8));
        } catch (JsonParseException exception) {
            throw new IOException("Invalid JSON", exception);
        } catch (RuntimeException exception) {
            throw new IOException("Invalid JSON", exception);
        }
    }

    public static JsonElement parse(InputStream input) throws IOException {
        if (input == null) {
            throw new IOException("JSON input is missing");
        }
        Reader reader = new InputStreamReader(input, StandardCharsets.UTF_8);
        try {
            return new JsonParser().parse(reader);
        } catch (JsonParseException exception) {
            throw new IOException("Invalid JSON", exception);
        } catch (RuntimeException exception) {
            throw new IOException("Invalid JSON", exception);
        }
    }

    public static byte[] toBytes(JsonElement element) {
        return GSON.toJson(element).getBytes(StandardCharsets.UTF_8);
    }

    public static String toJson(JsonElement element) {
        return GSON.toJson(element);
    }

    public static byte[] toPrettyBytes(JsonElement element) {
        return PRETTY_GSON.toJson(element).getBytes(StandardCharsets.UTF_8);
    }

    public static JsonElement get(JsonElement parent, String name) {
        if (parent == null || !parent.isJsonObject() || name == null) {
            return null;
        }
        JsonElement value = parent.getAsJsonObject().get(name);
        return value == null || value.isJsonNull() ? null : value;
    }

    public static String string(JsonElement value, String fallback) {
        if (value == null || value.isJsonNull() || !value.isJsonPrimitive()) {
            return fallback;
        }
        try {
            return value.getAsString();
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    public static String string(JsonElement parent, String name, String fallback) {
        return string(get(parent, name), fallback);
    }

    public static int integer(JsonElement value, int fallback) {
        if (value == null || !value.isJsonPrimitive()) {
            return fallback;
        }
        try {
            return value.getAsInt();
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    public static int integer(JsonElement parent, String name, int fallback) {
        return integer(get(parent, name), fallback);
    }

    public static long longValue(JsonElement value, long fallback) {
        if (value == null || !value.isJsonPrimitive()) {
            return fallback;
        }
        try {
            return value.getAsLong();
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    public static long longValue(JsonElement parent, String name, long fallback) {
        return longValue(get(parent, name), fallback);
    }

    public static double doubleValue(JsonElement value, double fallback) {
        if (value == null || !value.isJsonPrimitive()) {
            return fallback;
        }
        try {
            return value.getAsDouble();
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    public static double doubleValue(JsonElement parent, String name, double fallback) {
        return doubleValue(get(parent, name), fallback);
    }

    public static boolean booleanValue(JsonElement value, boolean fallback) {
        if (value == null || !value.isJsonPrimitive()) {
            return fallback;
        }
        try {
            return value.getAsBoolean();
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    public static boolean booleanValue(JsonElement parent, String name, boolean fallback) {
        return booleanValue(get(parent, name), fallback);
    }

    public static boolean isString(JsonElement value) {
        return primitive(value) != null && primitive(value).isString();
    }

    public static boolean isNumber(JsonElement value) {
        return primitive(value) != null && primitive(value).isNumber();
    }

    public static boolean isBoolean(JsonElement value) {
        return primitive(value) != null && primitive(value).isBoolean();
    }

    private static JsonPrimitive primitive(JsonElement value) {
        return value != null && value.isJsonPrimitive() ? value.getAsJsonPrimitive() : null;
    }
}
