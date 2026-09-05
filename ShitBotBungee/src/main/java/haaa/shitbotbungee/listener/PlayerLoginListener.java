package haaa.shitbotbungee.listener;

import haaa.shitbot.core.runtime.ShitBotRuntime;
import haaa.shitbot.core.config.Translations;
import haaa.shitbot.core.util.TextUtil;
import haaa.shitbot.core.service.LoginDecision;
import haaa.shitbot.core.update.UpdateChecker;
import haaa.shitbot.core.update.UpdateInfo;
import haaa.shitbotbungee.ShitBotBungee;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.event.PostLoginEvent;
import net.md_5.bungee.api.event.PreLoginEvent;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.event.EventHandler;
import net.md_5.bungee.event.EventPriority;

public final class PlayerLoginListener implements Listener {
    private final ShitBotBungee plugin;

    public PlayerLoginListener(ShitBotBungee plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPreLogin(final PreLoginEvent event) {
        if (event.isCancelled()) {
            return;
        }
        final ShitBotRuntime runtime = plugin.getRuntime();
        if (runtime == null) {
            // Fail closed whenever the runtime is unavailable, not just during startup failure.
            // `runtime` also goes null while the plugin is disabling/reloading; previously that
            // window silently allowed logins because only the startup-failure case was cancelled.
            String message = plugin.isStartupUnavailable()
                    ? message("messages.initialization-failed", "§cThe binding service is unavailable.")
                    : message("messages.reload-in-progress", "§cShitBot is reloading.");
            event.setCancelled(true);
            event.setCancelReason(TextComponent.fromLegacyText(message));
            return;
        }
        if (!runtime.getSettings().getBinding().isEnabled()) {
            return;
        }
        event.registerIntent(plugin);
        runtime.checkLogin(event.getConnection().getName(), null).whenComplete(
                new java.util.function.BiConsumer<LoginDecision, Throwable>() {
                    @Override
                    public void accept(LoginDecision decision, Throwable throwable) {
                        try {
                            if (throwable != null || decision == null || !decision.isAllowed()) {
                                String message = decision == null
                                        ? runtime.getSettings().getMessages().getKickDatabaseUnavailable()
                                        : decision.getMessage();
                                event.setCancelled(true);
                                event.setCancelReason(TextComponent.fromLegacyText(TextUtil.color(message)));
                            }
                        } finally {
                            event.completeIntent(plugin);
                        }
                    }
                });
    }

    @EventHandler
    public void onPostLogin(PostLoginEvent event) {
        ShitBotRuntime runtime = plugin.getRuntime();
        if (runtime != null && runtime.isReady()) {
            runtime.checkLogin(event.getPlayer().getName(), event.getPlayer().getUniqueId().toString());
        }
        final ProxiedPlayer player = event.getPlayer();
        if (!player.hasPermission("shitbot.admin")) {
            return;
        }
        final UpdateChecker updateChecker = plugin.getUpdateChecker();
        if (updateChecker == null) {
            return;
        }
        updateChecker.latestForNotificationAsync().thenAccept(
                new java.util.function.Consumer<UpdateInfo>() {
                    @Override
                    public void accept(final UpdateInfo info) {
                        if (!updateChecker.isUpdateAvailable(info)) {
                            return;
                        }
                        plugin.getPlatformBridge().executeOnPlatformThread(new Runnable() {
                            @Override
                            public void run() {
                                if (player.isConnected()) {
                                    plugin.sendUpdateNotice(player, info);
                                }
                            }
                        });
                    }
                });
    }

    private String message(String key, String fallback) {
        Translations translations = plugin.getTranslations();
        return TextUtil.color(translations == null ? fallback : translations.get(key, fallback));
    }
}
