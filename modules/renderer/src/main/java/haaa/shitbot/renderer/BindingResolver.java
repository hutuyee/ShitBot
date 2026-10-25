package haaa.shitbot.renderer;

import haaa.shitbot.api.ImageRenderRequest;

import java.lang.reflect.Array;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class BindingResolver {
    private static final Pattern EXACT = Pattern.compile("^\\$\\{([^{}]+)}$");
    private static final Pattern EMBEDDED = Pattern.compile("\\$\\{([^{}]+)}");

    private final Map<String, Object> roots;

    BindingResolver(ImageRenderRequest request) {
        Map<String, Object> values = new LinkedHashMap<String, Object>();
        values.put("context", request == null
                ? Collections.<String, Object>emptyMap() : request.getContext());
        values.put("data", request == null
                ? Collections.<String, Object>emptyMap() : request.getData());
        this.roots = Collections.unmodifiableMap(values);
    }

    private BindingResolver(Map<String, Object> roots) {
        this.roots = Collections.unmodifiableMap(roots);
    }

    BindingResolver with(String name, Object value) {
        Map<String, Object> copy = new LinkedHashMap<String, Object>(roots);
        copy.put(name, value);
        return new BindingResolver(copy);
    }

    Object value(Object expression) {
        if (!(expression instanceof String)) return expression;
        String text = (String) expression;
        Matcher exact = EXACT.matcher(text.trim());
        if (exact.matches()) return path(exact.group(1).trim());
        Matcher matcher = EMBEDDED.matcher(text);
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            Object value = path(matcher.group(1).trim());
            matcher.appendReplacement(result, Matcher.quoteReplacement(string(value)));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    String text(Object expression, String fallback) {
        Object value = value(expression);
        return value == null ? fallback : String.valueOf(value);
    }

    double number(Object expression, double fallback) {
        Object value = value(expression);
        if (value instanceof Number) return ((Number) value).doubleValue();
        try {
            return Double.parseDouble(value == null ? "" : String.valueOf(value).trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    boolean bool(Object expression, boolean fallback) {
        Object value = value(expression);
        if (value == null) return fallback;
        if (value instanceof Boolean) return ((Boolean) value).booleanValue();
        if (value instanceof Number) return ((Number) value).doubleValue() != 0.0D;
        String text = String.valueOf(value).trim();
        if (text.isEmpty()) return false;
        if ("true".equalsIgnoreCase(text) || "yes".equalsIgnoreCase(text)
                || "on".equalsIgnoreCase(text)) return true;
        if ("false".equalsIgnoreCase(text) || "no".equalsIgnoreCase(text)
                || "off".equalsIgnoreCase(text)) return false;
        return fallback;
    }

    boolean truthy(Object expression) {
        Object value = value(expression);
        if (value == null) return false;
        if (value instanceof Boolean) return ((Boolean) value).booleanValue();
        if (value instanceof Number) return ((Number) value).doubleValue() != 0.0D;
        if (value instanceof Map<?, ?>) return !((Map<?, ?>) value).isEmpty();
        if (value instanceof Iterable<?>) return ((Iterable<?>) value).iterator().hasNext();
        if (value.getClass().isArray()) return Array.getLength(value) > 0;
        String text = String.valueOf(value).trim();
        return !text.isEmpty() && !"false".equalsIgnoreCase(text) && !"0".equals(text);
    }

    private Object path(String expression) {
        if (expression.isEmpty()) return null;
        String[] parts = expression.split("\\.");
        Object current = roots.get(parts[0]);
        for (int index = 1; index < parts.length && current != null; index++) {
            String part = parts[index];
            if (current instanceof Map<?, ?>) {
                current = ((Map<?, ?>) current).get(part);
            } else if (current instanceof List<?>) {
                current = listValue((List<?>) current, part);
            } else if (current.getClass().isArray()) {
                current = arrayValue(current, part);
            } else {
                return null;
            }
        }
        return current;
    }

    private Object listValue(List<?> values, String index) {
        try {
            int parsed = Integer.parseInt(index);
            return parsed < 0 || parsed >= values.size() ? null : values.get(parsed);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private Object arrayValue(Object values, String index) {
        try {
            int parsed = Integer.parseInt(index);
            return parsed < 0 || parsed >= Array.getLength(values) ? null : Array.get(values, parsed);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
