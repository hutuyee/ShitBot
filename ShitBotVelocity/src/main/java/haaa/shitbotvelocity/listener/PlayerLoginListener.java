package haaa.shitbotvelocity.listener;

import com.velocitypowered.api.event.EventTask;
import com.velocitypowered.api.event.ResultedEvent;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import haaa.shitbot.core.runtime.ShitBotRuntime;
import haaa.shitbot.core.config.Translations;
import haaa.shitbot.core.util.TextUtil;
import haaa.shitbot.core.update.UpdateChecker;
import haaa.shitbot.core.update.UpdateInfo;
import haaa.shitbotvelocity.ShitBotVelocity;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

public final class PlayerLoginListener {
    private final ShitBotVelocity plugin;

    public PlayerLoginListener(ShitBotVelocity plugin) {
        this.plugin = plugin;
    }

    @Subscribe
    public EventTask onLogin(final LoginEvent event) {
        if (!event.getResult().isAllowed()) {
            return null;
        }
        final ShitBotRuntime runtime = plugin.getRuntime();
        if (runtime == null) {
            // Fail closed whenever the runtime is unavailable, not just during startup failure.
            // `runtime` also goes null while the plugin is disabling/reloading; previously that
            // window silently allowed logins because only the startup-failure case was denied.
            String message = plugin.isStartupUnavailable()
                    ? message("messages.initialization-failed", "§cThe binding service is unavailable.")
                    : message("messages.reload-in-progress", "§cShitBot is reloading.");
            event.setResult(ResultedEvent.ComponentResult.denied(
                    LegacyComponentSerializer.legacySection().deserialize(message)));
            return null;
        }
        if (!runtime.getSettings().getBinding().isEnabled()) {
            return null;
        }
        // LoginEvent exposes the authenticated profile; PreLoginEvent trusts client-supplied names.
        return EventTask.withContinuation(continuation ->
                runtime.checkLogin(event.getPlayer().getUsername(), event.getPlayer().getUniqueId().toString())
                        .whenComplete((decision, throwable) -> {
                            try {
                                if (throwable != null || decision == null || !decision.isAllowed()) {
                                    String message = decision == null
                                            ? runtime.getSettings().getMessages().getKickDatabaseUnavailable()
                                            : decision.getMessage();
                                    event.setResult(ResultedEvent.ComponentResult.denied(
                                            LegacyComponentSerializer.legacySection().deserialize(
                                                    TextUtil.color(message))));
                                }
                                continuation.resume();
                            } catch (Throwable callbackError) {
                                continuation.resumeWithException(callbackError);
                            }
                        }));
    }

    @Subscribe
    public void onPostLogin(PostLoginEvent event) {
        final com.velocitypowered.api.proxy.Player player = event.getPlayer();
        if (!player.hasPermission("shitbot.admin")) {
            return;
        }
        final UpdateChecker updateChecker = plugin.getUpdateChecker();
        if (updateChecker == null) {
            return;
        }
        updateChecker.latestForNotificationAsync().thenAccept((UpdateInfo info) -> {
            if (!updateChecker.isUpdateAvailable(info)) {
                return;
            }
            plugin.getPlatformBridge().executeOnPlatformThread(() -> {
                if (player.isActive()) {
                    plugin.sendUpdateNotice(player, info);
                }
            });
        });
    }

    private String message(String key, String fallback) {
        Translations translations = plugin.getTranslations();
        return TextUtil.color(translations == null ? fallback : translations.get(key, fallback));
    }
}
