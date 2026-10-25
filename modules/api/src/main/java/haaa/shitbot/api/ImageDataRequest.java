package haaa.shitbot.api;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Context passed to an explicitly declared template data provider. */
public final class ImageDataRequest {
    private final String templateId;
    private final Map<String, Object> context;
    private final Map<String, Object> options;

    public ImageDataRequest(String templateId,
                            Map<String, Object> context,
                            Map<String, Object> options) {
        this.templateId = templateId == null ? "" : templateId.trim();
        this.context = immutable(context);
        this.options = immutable(options);
    }

    public String getTemplateId() { return templateId; }
    public Map<String, Object> getContext() { return context; }
    public Map<String, Object> getOptions() { return options; }

    private static Map<String, Object> immutable(Map<String, Object> values) {
        return values == null || values.isEmpty()
                ? Collections.<String, Object>emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<String, Object>(values));
    }
}
