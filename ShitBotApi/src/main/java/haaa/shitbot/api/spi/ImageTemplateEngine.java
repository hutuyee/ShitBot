package haaa.shitbot.api.spi;

import haaa.shitbot.api.ImageRenderRequest;
import haaa.shitbot.api.ImageRenderResult;
import haaa.shitbot.api.ImageTemplateInfo;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/** Service-provider contract implemented by the separately downloaded renderer component. */
public interface ImageTemplateEngine extends AutoCloseable {
    CompletableFuture<Void> startAsync();

    CompletableFuture<ImageRenderResult> render(String templateId, ImageRenderRequest request);

    List<ImageTemplateInfo> getTemplates();

    Optional<ImageTemplateInfo> getTemplate(String templateId);

    CompletableFuture<String> createEditorLoginUrl();

    @Override
    void close();
}
