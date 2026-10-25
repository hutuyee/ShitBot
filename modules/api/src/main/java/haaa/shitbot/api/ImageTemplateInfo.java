package haaa.shitbot.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Public metadata for the currently published version of one template. */
public final class ImageTemplateInfo {
    private final String id;
    private final String name;
    private final long version;
    private final int width;
    private final int height;
    private final List<ImageDataProviderSpec> providers;

    public ImageTemplateInfo(String id,
                             String name,
                             long version,
                             int width,
                             int height,
                             List<ImageDataProviderSpec> providers) {
        this.id = id == null ? "" : id.trim();
        this.name = name == null || name.trim().isEmpty() ? this.id : name.trim();
        this.version = Math.max(1L, version);
        this.width = width;
        this.height = height;
        this.providers = providers == null
                ? Collections.<ImageDataProviderSpec>emptyList()
                : Collections.unmodifiableList(new ArrayList<ImageDataProviderSpec>(providers));
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public long getVersion() { return version; }
    public int getWidth() { return width; }
    public int getHeight() { return height; }
    public List<ImageDataProviderSpec> getProviders() { return providers; }
}
