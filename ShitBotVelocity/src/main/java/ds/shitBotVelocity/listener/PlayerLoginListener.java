package ds.shitBotVelocity.listener;

import com.velocitypowered.api.event.EventTask;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.connection.PreLoginEvent;
import ds.shitBotVelocity.ShitBotVelocity;
import haaa.shitbot.core.runtime.ShitBotRuntime;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

public final class PlayerLoginListener {
    private final ShitBotVelocity plugin;

    public PlayerLoginListener(ShitBotVelocity plugin) {
        this.plugin = plugin;
    }

    @Subscribe
    public EventTask onPreLogin(final PreLoginEvent event) {
        if (!event.getResult().isAllowed()) {
            return null;
        }
        final ShitBotRuntime runtime = plugin.getRuntime();
        if (runtime == null) {
            if (plugin.isStartupUnavailable()) {
                event.setResult(PreLoginEvent.PreLoginComponentResult.denied(
                        LegacyComponentSerializer.legacySection().deserialize(
                                "§c绑定系统配置加载失败，请联系管理员。")));
            }
            return null;
        }
        if (!runtime.getSettings().getBinding().isEnabled()) {
            return null;
        }
        return EventTask.withContinuation(continuation ->
                runtime.checkLogin(event.getUsername(), null).whenComplete((decision, throwable) -> {
                    try {
                        if (throwable != null || decision == null || !decision.isAllowed()) {
                            String message = decision == null
                                    ? runtime.getSettings().getMessages().getKickDatabaseUnavailable()
                                    : decision.getMessage();
                            event.setResult(PreLoginEvent.PreLoginComponentResult.denied(
                                    LegacyComponentSerializer.legacySection().deserialize(message)));
                        }
                        continuation.resume();
                    } catch (Throwable callbackError) {
                        continuation.resumeWithException(callbackError);
                    }
                }));
    }

    @Subscribe
    public void onPostLogin(PostLoginEvent event) {
        ShitBotRuntime runtime = plugin.getRuntime();
        if (runtime != null && runtime.isReady()) {
            runtime.checkLogin(event.getPlayer().getUsername(), event.getPlayer().getUniqueId().toString());
        }
    }
}
