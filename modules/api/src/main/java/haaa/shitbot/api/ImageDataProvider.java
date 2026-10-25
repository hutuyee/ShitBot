package haaa.shitbot.api;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** Extension point for data resolved before a template enters a rendering thread. */
public interface ImageDataProvider {
    String getId();

    CompletableFuture<Map<String, Object>> provide(ImageDataRequest request);
}
