package haaa.shitbot.api;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** A provider declaration read from a template manifest. */
public final class ImageDataProviderSpec {
    private final String id;
    private final Map<String, Object> options;

    public ImageDataProviderSpec(String id, Map<String, ?> options) {
        if (id == null || !id.trim().matches("[a-z0-9][a-z0-9_-]{0,63}")) {
            throw new IllegalArgumentException("invalid image data provider id: " + id);
        }
        this.id = id.trim();
        Map<String, Object> copied = new LinkedHashMap<String, Object>();
        if (options != null) {
            for (Map.Entry<String, ?> entry : options.entrySet()) {
                if (entry.getKey() != null) {
                    copied.put(entry.getKey(), entry.getValue());
                }
            }
        }
        this.options = Collections.unmodifiableMap(copied);
    }

    public String getId() { return id; }
    public Map<String, Object> getOptions() { return options; }
}
