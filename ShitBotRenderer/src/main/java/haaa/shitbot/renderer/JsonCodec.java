package haaa.shitbot.renderer;

import java.lang.reflect.Array;
import java.util.Iterator;
import java.util.Map;

final class JsonCodec {
    private JsonCodec() {
    }

    static String encode(Object value) {
        StringBuilder output = new StringBuilder();
        append(output, value);
        return output.toString();
    }

    private static void append(StringBuilder output, Object value) {
        if (value == null) {
            output.append("null");
        } else if (value instanceof Boolean || value instanceof Byte || value instanceof Short
                || value instanceof Integer || value instanceof Long) {
            output.append(value);
        } else if (value instanceof Number) {
            double number = ((Number) value).doubleValue();
            output.append(Double.isNaN(number) || Double.isInfinite(number) ? "null" : value);
        } else if (value instanceof Map<?, ?>) {
            output.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                if (entry.getKey() == null) continue;
                if (!first) output.append(',');
                first = false;
                string(output, String.valueOf(entry.getKey()));
                output.append(':');
                append(output, entry.getValue());
            }
            output.append('}');
        } else if (value instanceof Iterable<?>) {
            output.append('[');
            Iterator<?> iterator = ((Iterable<?>) value).iterator();
            boolean first = true;
            while (iterator.hasNext()) {
                if (!first) output.append(',');
                first = false;
                append(output, iterator.next());
            }
            output.append(']');
        } else if (value.getClass().isArray()) {
            output.append('[');
            int length = Array.getLength(value);
            for (int index = 0; index < length; index++) {
                if (index > 0) output.append(',');
                append(output, Array.get(value, index));
            }
            output.append(']');
        } else {
            string(output, String.valueOf(value));
        }
    }

    private static void string(StringBuilder output, String value) {
        output.append('"');
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            switch (current) {
                case '"': output.append("\\\""); break;
                case '\\': output.append("\\\\"); break;
                case '\b': output.append("\\b"); break;
                case '\f': output.append("\\f"); break;
                case '\n': output.append("\\n"); break;
                case '\r': output.append("\\r"); break;
                case '\t': output.append("\\t"); break;
                default:
                    if (current < 0x20) output.append(String.format("\\u%04x", (int) current));
                    else output.append(current);
            }
        }
        output.append('"');
    }
}
