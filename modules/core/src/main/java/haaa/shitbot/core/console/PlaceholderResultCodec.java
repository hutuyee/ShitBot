package haaa.shitbot.core.console;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/** Compact ASCII encoding for PlaceholderAPI values crossing the authenticated backend link. */
public final class PlaceholderResultCodec {
    private static final int MAX_ENCODED_LENGTH = 12000;
    private static final int MAX_VALUE_LENGTH = 512;

    private PlaceholderResultCodec() {
    }

    public static String encode(Map<String, String> values) throws IOException {
        StringBuilder result = new StringBuilder();
        if (values != null) {
            for (Map.Entry<String, String> entry : values.entrySet()) {
                if (entry.getKey() == null) continue;
                if (result.length() > 0) result.append('\n');
                result.append(base64(limit(entry.getKey()))).append('=')
                        .append(base64(limit(entry.getValue())));
                if (result.length() > MAX_ENCODED_LENGTH) {
                    throw new IOException("Placeholder result exceeds the backend protocol limit");
                }
            }
        }
        return result.toString();
    }

    public static Map<String, String> decode(String encoded) throws IOException {
        Map<String, String> result = new LinkedHashMap<String, String>();
        if (encoded == null || encoded.isEmpty()) return result;
        if (encoded.length() > MAX_ENCODED_LENGTH) throw new IOException("Placeholder result is too large");
        for (String line : encoded.split("\\n", -1)) {
            int separator = line.indexOf('=');
            if (separator <= 0) throw new IOException("Placeholder result is malformed");
            try {
                String key = text(line.substring(0, separator));
                String value = text(line.substring(separator + 1));
                if (key.isEmpty() || result.put(key, value) != null) {
                    throw new IOException("Placeholder result contains an invalid or duplicate key");
                }
            } catch (IllegalArgumentException exception) {
                throw new IOException("Placeholder result is malformed", exception);
            }
        }
        return result;
    }

    private static String base64(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                value.getBytes(StandardCharsets.UTF_8));
    }

    private static String text(String value) {
        return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
    }

    private static String limit(String value) {
        String text = value == null ? "" : value;
        return text.length() <= MAX_VALUE_LENGTH ? text : text.substring(0, MAX_VALUE_LENGTH);
    }
}
