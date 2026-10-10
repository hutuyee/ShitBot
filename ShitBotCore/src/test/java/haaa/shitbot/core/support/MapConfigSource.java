package haaa.shitbot.core.support;

import haaa.shitbot.core.config.ConfigSource;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Small ordered configuration fixture; keys use the same dotted paths as platform loaders. */
public final class MapConfigSource implements ConfigSource {
    private final Map<String, Object> values = new LinkedHashMap<String, Object>();

    public MapConfigSource put(String path, Object value) {
        values.put(path, value);
        return this;
    }

    @Override
    public String getString(String path, String fallback) {
        Object value = values.get(path);
        return value == null ? fallback : String.valueOf(value);
    }

    @Override
    public boolean getBoolean(String path, boolean fallback) {
        Object value = values.get(path);
        return value instanceof Boolean ? (Boolean) value : fallback;
    }

    @Override
    public int getInt(String path, int fallback) {
        Object value = values.get(path);
        return value instanceof Number ? ((Number) value).intValue() : fallback;
    }

    @Override
    public long getLong(String path, long fallback) {
        Object value = values.get(path);
        return value instanceof Number ? ((Number) value).longValue() : fallback;
    }

    @Override
    public List<String> getStringList(String path) {
        Object value = values.get(path);
        if (!(value instanceof List<?>)) return Collections.emptyList();
        List<String> result = new ArrayList<String>();
        for (Object item : (List<?>) value) result.add(item == null ? null : String.valueOf(item));
        return result;
    }

    @Override
    public List<Long> getLongList(String path) {
        Object value = values.get(path);
        if (!(value instanceof List<?>)) return Collections.emptyList();
        List<Long> result = new ArrayList<Long>();
        for (Object item : (List<?>) value) {
            if (item instanceof Number) result.add(((Number) item).longValue());
        }
        return result;
    }

    @Override
    public Set<String> getSectionKeys(String path) {
        Set<String> result = new LinkedHashSet<String>();
        String prefix = path + ".";
        for (String key : values.keySet()) {
            if (key.startsWith(prefix)) result.add(key.substring(prefix.length()).split("\\.")[0]);
        }
        return result;
    }
}
