package haaa.shitbot.api.spi;

import haaa.shitbot.api.ImageDataProviderSpec;
import haaa.shitbot.api.ImageRenderRequest;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** Narrow host bridge used by the optional renderer without platform dependencies. */
public interface ImageTemplateEngineHost {
    CompletableFuture<Map<String, Object>> resolveData(String templateId,
                                                       List<ImageDataProviderSpec> providers,
                                                       ImageRenderRequest request);

    void info(String message);
    void warn(String message);
    void error(String message, Throwable throwable);
}
