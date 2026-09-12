package haaa.shitbot.api;

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

    CompletableFuture<ImageRenderResult> renderImage(String templateId, ImageRenderRequest request);

    List<ImageTemplateInfo> getImageTemplates();

    Optional<ImageTemplateInfo> getImageTemplate(String templateId);

    void registerImageDataProvider(ImageDataProvider provider);

    boolean unregisterImageDataProvider(String providerId, ImageDataProvider provider);

    /** Creates a short-lived, one-use local editor URL. */
    CompletableFuture<String> createImageEditorLoginUrl();
}
