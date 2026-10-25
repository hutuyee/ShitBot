package haaa.shitbot.api;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Immutable input supplied to one published image template render. */
public final class ImageRenderRequest {
    private final Map<String, Object> context;
    private final Map<String, Object> data;

    public ImageRenderRequest(Map<String, ?> context, Map<String, ?> data) {
        this.context = immutableCopy(context);
        this.data = immutableCopy(data);
    }

    public static ImageRenderRequest of(Map<String, ?> context) {
        return new ImageRenderRequest(context, Collections.<String, Object>emptyMap());
    }

    public Map<String, Object> getContext() {
        return context;
    }

    public Map<String, Object> getData() {
        return data;
    }

    public ImageRenderRequest withData(Map<String, ?> resolvedData) {
        Map<String, Object> merged = new LinkedHashMap<String, Object>(data);
        if (resolvedData != null) {
            for (Map.Entry<String, ?> entry : resolvedData.entrySet()) {
                if (entry.getKey() != null && !entry.getKey().trim().isEmpty()) {
                    merged.put(entry.getKey(), entry.getValue());
                }
            }
        }
        return new ImageRenderRequest(context, merged);
    }

    private static Map<String, Object> immutableCopy(Map<String, ?> source) {
        if (source == null || source.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, Object> copy = new LinkedHashMap<String, Object>();
        for (Map.Entry<String, ?> entry : source.entrySet()) {
            if (entry.getKey() != null && !entry.getKey().trim().isEmpty()) {
                copy.put(entry.getKey(), entry.getValue());
            }
        }
        return Collections.unmodifiableMap(copy);
    }
}
