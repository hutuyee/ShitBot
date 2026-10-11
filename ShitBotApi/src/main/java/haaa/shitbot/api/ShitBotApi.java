package haaa.shitbot.api;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/** Stable, platform-neutral entry point exposed by every ShitBot build. */
public interface ShitBotApi {
    /** False before startup completes or after this runtime has been replaced/closed. */
    boolean isReady();

    /** Administrative lookup, including entries without a QQ owner. */
    CompletableFuture<Optional<PlayerBinding>> getBinding(String playerName);

    /** All characters owned by a valid QQ number. Blank/invalid QQ numbers return no entries. */
    CompletableFuture<List<PlayerBinding>> getBindingsByQq(String qqId);

    /** Administrative listing in insertion order. Offset >= 0, limit from 1 to 100. */
    CompletableFuture<List<PlayerBinding>> getWhitelist(int offset, int limit);

    /** Grants login without a QQ owner or any QQ inventory/character-query rights. */
    CompletableFuture<BindingResult> addWhitelist(String playerName);

    /**
     * Administrative grant without a verification code. Null/blank QQ grants login only.
     * Never overwrites an existing owner, and respects the configured per-QQ binding limit.
     * Calling plugins must perform their own permission checks.
     */
    CompletableFuture<BindingResult> addWhitelist(String playerName, String qqId);

    /** Normal QQ binding with the same verification-code and rate-limit rules as chat. */
    CompletableFuture<BindingResult> bind(String playerName, String qqId, String verificationCode);

    /** Removes one exact player entry and schedules disconnection of that player. */
    CompletableFuture<Optional<PlayerBinding>> removeWhitelist(String playerName);

    /** Removes all entries for this QQ and schedules disconnection of their players. */
    CompletableFuture<List<PlayerBinding>> removeBindingsByQq(String qqId);

    boolean isCustomImageTemplatesEnabled();

    /** Uses image.renderer and the existing online-image cache, including custom templates. */
    CompletableFuture<ImageRenderResult> renderOnlineImage();

    /** Native profile card. The calling plugin is responsible for authorizing player access. */
    CompletableFuture<ImageRenderResult> renderPlayerProfileImage(String playerName);

    boolean isInventoryEnabled();

    /** Administrative native inventory image; does not require a QQ binding. */
    CompletableFuture<InventoryImageResult> renderInventoryImage(String playerName);

    /** Selects an owned character using the same rules as the QQ inventory command. */
    default CompletableFuture<InventoryImageResult> renderInventoryImageForQq(String qqId) {
        return renderInventoryImageForQq(qqId, null);
    }

    /** Verifies QQ ownership. Null/blank player names select an owned character automatically. */
    CompletableFuture<InventoryImageResult> renderInventoryImageForQq(String qqId, String playerName);

    /** Renders a published advanced template; requires custom-image-templates.enabled. */
    CompletableFuture<ImageRenderResult> renderImage(String templateId, ImageRenderRequest request);

    /**
     * Saves this exact result asynchronously, creates parent directories and replaces the target file.
     * Returns an absolute normalized local path; path.toUri() is a file URI, not a hosted HTTP URL.
     * The calling plugin owns destination selection, authorization and file retention.
     * The bounded writer rejects excess work and releases pending futures when the runtime closes.
     */
    CompletableFuture<Path> saveImage(ImageRenderResult image, Path target);

    List<ImageTemplateInfo> getImageTemplates();

    Optional<ImageTemplateInfo> getImageTemplate(String templateId);

    void registerImageDataProvider(ImageDataProvider provider);

    boolean unregisterImageDataProvider(String providerId, ImageDataProvider provider);

    /** Creates a short-lived, one-use local editor URL. */
    CompletableFuture<String> createImageEditorLoginUrl();
}
