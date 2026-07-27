package haaa.shitbotbungee.listener;

import haaa.shitbot.core.runtime.ShitBotRuntime;
import haaa.shitbot.core.service.LoginDecision;
import haaa.shitbotbungee.ShitBotBungee;
import net.md_5.bungee.api.chat.TextComponent;
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
            if (plugin.isStartupUnavailable()) {
                event.setCancelled(true);
                event.setCancelReason(TextComponent.fromLegacyText(
                        "§c绑定系统配置加载失败，请联系管理员。"));
            }
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
                                event.setCancelReason(TextComponent.fromLegacyText(message));
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
    }
}
