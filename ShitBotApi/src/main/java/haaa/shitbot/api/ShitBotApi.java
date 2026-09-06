package haaa.shitbot.api;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/** Stable, platform-neutral entry point exposed by every ShitBot build. */
public interface ShitBotApi {
    boolean isCustomImageTemplatesEnabled();

    CompletableFuture<ImageRenderResult> renderImage(String templateId, ImageRenderRequest request);

    List<ImageTemplateInfo> getImageTemplates();

    Optional<ImageTemplateInfo> getImageTemplate(String templateId);

    void registerImageDataProvider(ImageDataProvider provider);

    boolean unregisterImageDataProvider(String providerId, ImageDataProvider provider);

    /** Creates a short-lived, one-use local editor URL. */
    CompletableFuture<String> createImageEditorLoginUrl();
}
