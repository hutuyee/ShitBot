package haaa.shitbotspigot.listener;

import haaa.shitbot.core.runtime.ShitBotRuntime;
import haaa.shitbot.core.service.LoginDecision;
import haaa.shitbotspigot.ShitBotSpigot;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;

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
            if (plugin.isStartupUnavailable()) {
                event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                        "§c绑定系统配置加载失败，请联系管理员。");
            }
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
}
