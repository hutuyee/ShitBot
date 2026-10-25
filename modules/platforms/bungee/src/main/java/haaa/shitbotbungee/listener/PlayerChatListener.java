package haaa.shitbotbungee.listener;

import haaa.shitbot.core.runtime.ShitBotRuntime;
import haaa.shitbotbungee.ShitBotBungee;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.event.ChatEvent;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.event.EventHandler;
import net.md_5.bungee.event.EventPriority;

public final class PlayerChatListener implements Listener {
    private final ShitBotBungee plugin;

    public PlayerChatListener(ShitBotBungee plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerChat(ChatEvent event) {
        if (event.isCancelled() || event.isCommand() || !(event.getSender() instanceof ProxiedPlayer)) {
            return;
        }
        ShitBotRuntime runtime = plugin.getRuntime();
        if (runtime != null) {
            ProxiedPlayer player = (ProxiedPlayer) event.getSender();
            runtime.forwardGameMessage(player.getName(), event.getMessage());
        }
    }
}
