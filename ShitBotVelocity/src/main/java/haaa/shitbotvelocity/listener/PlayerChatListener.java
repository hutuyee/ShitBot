package haaa.shitbotvelocity.listener;

import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.PlayerChatEvent;
import haaa.shitbotvelocity.ShitBotVelocity;
import haaa.shitbot.core.runtime.ShitBotRuntime;

public final class PlayerChatListener {
    private final ShitBotVelocity plugin;

    public PlayerChatListener(ShitBotVelocity plugin) {
        this.plugin = plugin;
    }

    @Subscribe(order = PostOrder.LAST)
    public void onPlayerChat(PlayerChatEvent event) {
        if (!event.getResult().isAllowed()) {
            return;
        }
        ShitBotRuntime runtime = plugin.getRuntime();
        if (runtime != null) {
            String message = event.getResult().getMessage().orElse(event.getMessage());
            runtime.forwardGameMessage(event.getPlayer().getUsername(), message);
        }
    }
}
