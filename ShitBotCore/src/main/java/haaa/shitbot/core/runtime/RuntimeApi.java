package haaa.shitbot.core.runtime;

import haaa.shitbot.api.*;
import haaa.shitbot.core.database.BindingRecord;
import haaa.shitbot.core.util.FutureUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/** Public API facade. No optional renderer is needed for account operations. */
final class RuntimeApi implements ShitBotApi {
    private final ShitBotRuntime runtime;
    private final haaa.shitbot.core.image.CustomImageService images;

    RuntimeApi(ShitBotRuntime runtime, haaa.shitbot.core.image.CustomImageService images) {
        this.runtime = runtime;
        this.images = images;
    }

    @Override public boolean isReady() { return runtime.isReady(); }

    @Override public CompletableFuture<Optional<PlayerBinding>> getBinding(String playerName) {
        return ready(() -> runtime.getBindingService().findByPlayerName(playerName)
                .thenApply(value -> value.map(RuntimeApi::binding)));
    }

    @Override public CompletableFuture<List<PlayerBinding>> getBindingsByQq(String qqId) {
        return ready(() -> runtime.getBindingService().findAllByQqId(qqId).thenApply(RuntimeApi::bindings));
    }

    @Override public CompletableFuture<List<PlayerBinding>> getWhitelist(int offset, int limit) {
        return ready(() -> runtime.getBindingService().listWhitelist(offset, limit).thenApply(RuntimeApi::bindings));
    }

    @Override public CompletableFuture<BindingResult> addWhitelist(String playerName) {
        return addWhitelist(playerName, null);
    }

    @Override public CompletableFuture<BindingResult> addWhitelist(String playerName, String qqId) {
        return ready(() -> runtime.getBindingService().addWhitelist(playerName, qqId).thenApply(RuntimeApi::result));
    }

    @Override public CompletableFuture<BindingResult> bind(String playerName, String qqId, String verificationCode) {
        return ready(() -> runtime.getBindingService().bind(playerName, qqId, verificationCode)
                .thenApply(RuntimeApi::result));
    }

    @Override public CompletableFuture<Optional<PlayerBinding>> removeWhitelist(String playerName) {
        return ready(() -> runtime.getBindingService().removeWhitelist(playerName)
                .thenApply(value -> value.map(RuntimeApi::binding)));
    }

    @Override public CompletableFuture<List<PlayerBinding>> removeBindingsByQq(String qqId) {
        return ready(() -> runtime.getBindingService().unbindByQqId(qqId).thenApply(RuntimeApi::bindings));
    }

    @Override public boolean isCustomImageTemplatesEnabled() { return images.isCustomImageTemplatesEnabled(); }
    @Override public CompletableFuture<ImageRenderResult> renderImage(String id, ImageRenderRequest request) {
        return ready(() -> images.renderImage(id, request));
    }
    @Override public List<ImageTemplateInfo> getImageTemplates() { return images.getImageTemplates(); }
    @Override public Optional<ImageTemplateInfo> getImageTemplate(String id) { return images.getImageTemplate(id); }
    @Override public void registerImageDataProvider(ImageDataProvider provider) {
        if (!isReady()) throw new IllegalStateException("ShitBot runtime is not ready; reacquire the API after reload");
        images.registerImageDataProvider(provider);
    }
    @Override public boolean unregisterImageDataProvider(String id, ImageDataProvider provider) {
        return images.unregisterImageDataProvider(id, provider);
    }
    @Override public CompletableFuture<String> createImageEditorLoginUrl() {
        return ready(images::createImageEditorLoginUrl);
    }

    private <T> CompletableFuture<T> ready(Supplier<CompletableFuture<T>> operation) {
        if (!isReady()) return FutureUtil.failedFuture(new IllegalStateException(
                "ShitBot runtime is not ready; reacquire the API after reload"));
        try {
            return operation.get();
        } catch (RuntimeException exception) {
            return FutureUtil.failedFuture(exception);
        }
    }

    private static PlayerBinding binding(BindingRecord record) {
        return new PlayerBinding(record.getPlayerName(), record.getPlayerUuid(), record.getQqId(),
                record.getCreatedAt(), record.getUpdatedAt());
    }

    private static List<PlayerBinding> bindings(List<BindingRecord> records) {
        List<PlayerBinding> result = new ArrayList<PlayerBinding>(records.size());
        for (BindingRecord record : records) result.add(binding(record));
        return Collections.unmodifiableList(result);
    }

    private static BindingResult result(haaa.shitbot.core.database.BindResult result) {
        return new BindingResult(BindingResult.Status.valueOf(result.getStatus().name()),
                result.getBinding() == null ? null : binding(result.getBinding()));
    }
}
