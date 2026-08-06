package haaa.shitbotspigot.listener;

import haaa.shitbot.core.runtime.ShitBotRuntime;
import haaa.shitbot.core.util.FutureUtil;
import haaa.shitbotspigot.ShitBotSpigot;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

/** Captures the final readable inventory while the quitting Player is still attached. */
public final class PlayerInventorySnapshotListener implements Listener {
    private final ShitBotSpigot plugin;

    public PlayerInventorySnapshotListener(ShitBotSpigot plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        ShitBotRuntime runtime = plugin.getRuntime();
        if (runtime == null || !runtime.getSettings().getInventory().isEnabled()) {
            return;
        }
        runtime.getInventoryService()
                .persistSnapshot(plugin.getPlatformBridge().captureInventorySnapshot(event.getPlayer()))
                .exceptionally(new java.util.function.Function<Throwable, Void>() {
                    @Override
                    public Void apply(Throwable throwable) {
                        plugin.getPlatformBridge().warn("Failed to save quit inventory snapshot: "
                                + FutureUtil.unwrap(throwable).getMessage());
                        return null;
                    }
                });
    }
}
