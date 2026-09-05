package haaa.shitbotspigot.listener;

import haaa.shitbot.core.runtime.ShitBotRuntime;
import haaa.shitbot.core.config.Translations;
import haaa.shitbot.core.util.TextUtil;
import haaa.shitbot.core.service.LoginDecision;
import haaa.shitbot.core.update.UpdateChecker;
import haaa.shitbot.core.update.UpdateInfo;
import haaa.shitbotspigot.ShitBotSpigot;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;

import java.util.concurrent.TimeUnit;

public final class PlayerLoginListener implements Listener {
    private final ShitBotSpigot plugin;

    public PlayerLoginListener(ShitBotSpigot plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onAsyncPreLogin(AsyncPlayerPreLoginEvent event) {
        ShitBotRuntime runtime = plugin.getRuntime();
        if (runtime == null) {
            // Fail closed whenever the runtime is unavailable, not just during startup failure.
            // `runtime` also goes null while the plugin is disabling/reloading; previously that
            // window silently allowed logins because only the startup-failure case was kicked.
            String message = plugin.isStartupUnavailable()
                    ? message("messages.initialization-failed", "§cThe binding service is unavailable.")
                    : message("messages.reload-in-progress", "§cShitBot is reloading.");
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, message);
            return;
        }
        if (!runtime.getSettings().getBinding().isEnabled()) {
            return;
        }
        try {
            LoginDecision decision = runtime.checkLogin(event.getName(), event.getUniqueId().toString())
                    .get(runtime.getSettings().getBinding().getLoginDatabaseTimeoutSeconds(), TimeUnit.SECONDS);
            if (!decision.isAllowed()) {
                event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, decision.getMessage());
            }
        } catch (Throwable throwable) {
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                    haaa.shitbot.core.util.TextUtil.color(
                            runtime.getSettings().getMessages().getKickDatabaseUnavailable()));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onJoin(final PlayerJoinEvent event) {
        final Player player = event.getPlayer();
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
                        plugin.getPlatformBridge().executeOnSenderThread(player, new Runnable() {
                            @Override
                            public void run() {
                                if (player.isOnline()) {
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
