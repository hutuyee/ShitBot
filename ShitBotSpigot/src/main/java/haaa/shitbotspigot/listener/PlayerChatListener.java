package haaa.shitbotspigot.listener;

import haaa.shitbot.core.runtime.ShitBotRuntime;
import haaa.shitbotspigot.ShitBotSpigot;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;

public final class PlayerChatListener implements Listener {
    private final ShitBotSpigot plugin;

    public PlayerChatListener(ShitBotSpigot plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        ShitBotRuntime runtime = plugin.getRuntime();
        if (runtime != null) {
            runtime.forwardGameMessage(event.getPlayer().getName(), event.getMessage());
        }
    }
}
