package haaa.shitbot.core.runtime;

import haaa.shitbot.api.*;
import haaa.shitbot.core.database.BindingRecord;
import haaa.shitbot.core.image.ImageFileService;
import haaa.shitbot.core.inventory.InventorySnapshot;
import haaa.shitbot.core.service.InventoryQueryResult;
import haaa.shitbot.core.util.FutureUtil;
import haaa.shitbot.core.util.TextUtil;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Supplier;

/** Public API facade. No optional renderer is needed for account operations. */
final class RuntimeApi implements ShitBotApi, AutoCloseable {
    private static final int MAXIMUM_IMAGE_REQUESTS = 32;
    private final ShitBotRuntime runtime;
    private final haaa.shitbot.core.image.CustomImageService images;
    private final ImageFileService files = new ImageFileService();
    private final Set<CompletableFuture<?>> imageRequests = new HashSet<CompletableFuture<?>>();
    private final Map<String, CompletableFuture<ImageRenderResult>> profileRequests =
            new HashMap<String, CompletableFuture<ImageRenderResult>>();
    private boolean closed;

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

    @Override public CompletableFuture<ImageRenderResult> renderOnlineImage() {
        return imageOperation(() -> runtime.getImageService().renderOnlineImageResultAsync());
    }

    @Override public CompletableFuture<ImageRenderResult> renderPlayerProfileImage(String playerName) {
        return imageOperation(() -> {
            String player = playerName(playerName);
            final CompletableFuture<ImageRenderResult> created;
            synchronized (profileRequests) {
                CompletableFuture<ImageRenderResult> existing = profileRequests.get(player);
                if (existing != null) return existing;
                created = runtime.getProfileService().find(player)
                        .thenCompose(profile -> runtime.getProfileImageService().renderResultAsync(profile));
                profileRequests.put(player, created);
            }
            created.whenComplete((image, failure) -> {
                synchronized (profileRequests) { profileRequests.remove(player, created); }
            });
            return created;
        });
    }

    @Override public boolean isInventoryEnabled() { return runtime.getSettings().getInventory().isEnabled(); }

    @Override public CompletableFuture<InventoryImageResult> renderInventoryImage(String playerName) {
        return imageOperation(() -> runtime.getInventoryService().queryForPlayer(playerName(playerName))
                .thenApply(RuntimeApi::inventory));
    }

    @Override public CompletableFuture<InventoryImageResult> renderInventoryImageForQq(String qqId,
                                                                                       String playerName) {
        return imageOperation(() -> {
            if (!TextUtil.isValidQqId(qqId)) throw new IllegalArgumentException("Invalid QQ number");
            String player = playerName == null || playerName.trim().isEmpty() ? null : playerName(playerName);
            return runtime.getInventoryService().queryForQq(qqId.trim(), player).thenApply(RuntimeApi::inventory);
        });
    }

    @Override public CompletableFuture<ImageRenderResult> renderImage(String id, ImageRenderRequest request) {
        return imageOperation(() -> images.renderImage(id, request));
    }

    @Override public CompletableFuture<Path> saveImage(ImageRenderResult image, Path target) {
        return ready(() -> files.save(image, target));
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

    /** Admission happens before data collection; cancelling a caller future cannot bypass the bound. */
    private <T> CompletableFuture<T> imageOperation(Supplier<CompletableFuture<T>> operation) {
        return ready(() -> {
            final CompletableFuture<T> result = new CompletableFuture<T>();
            synchronized (imageRequests) {
                if (closed) throw new IllegalStateException("ShitBot image API is closed");
                if (imageRequests.size() >= MAXIMUM_IMAGE_REQUESTS) {
                    throw new RejectedExecutionException("ShitBot image API has 32 unfinished requests");
                }
                imageRequests.add(result);
            }
            try {
                operation.get().whenComplete((value, failure) -> finishImage(result, value, failure));
            } catch (Throwable failure) {
                finishImage(result, null, failure);
            }
            return result.thenApply(value -> value);
        });
    }

    private <T> void finishImage(CompletableFuture<T> result, T value, Throwable failure) {
        synchronized (imageRequests) { imageRequests.remove(result); }
        if (failure != null) result.completeExceptionally(failure);
        else if (!runtime.isReady()) result.completeExceptionally(new IllegalStateException(
                "ShitBot runtime is not ready; reacquire the API after reload"));
        else result.complete(value);
    }

    private static String playerName(String value) {
        if (!TextUtil.isValidPlayerName(value) || value.trim().isEmpty()) {
            throw new IllegalArgumentException("Invalid player name");
        }
        return value.trim();
    }

    private static InventoryImageResult inventory(InventoryQueryResult result) {
        if (result.getStatus() != InventoryQueryResult.Status.SUCCESS) {
            return InventoryImageResult.status(InventoryImageResult.Status.valueOf(result.getStatus().name()));
        }
        InventorySnapshot snapshot = result.getSnapshot();
        return InventoryImageResult.success(result.getRenderResult(), snapshot.getPlayerName(), snapshot.getServerName(),
                snapshot.getCapturedAt(), result.isLive());
    }

    @Override public void close() {
        List<CompletableFuture<?>> unfinished;
        synchronized (imageRequests) {
            if (closed) return;
            closed = true;
            unfinished = new ArrayList<CompletableFuture<?>>(imageRequests);
            imageRequests.clear();
        }
        synchronized (profileRequests) { profileRequests.clear(); }
        IllegalStateException failure = new IllegalStateException(
                "ShitBot runtime is closed; reacquire the API after reload");
        for (CompletableFuture<?> result : unfinished) result.completeExceptionally(failure);
        files.close();
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
