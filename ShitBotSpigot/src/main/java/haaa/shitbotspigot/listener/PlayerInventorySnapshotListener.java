package haaa.shitbotspigot.listener;

import haaa.shitbot.core.inventory.InventorySnapshot;
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
        final InventorySnapshot snapshot;
        try {
            snapshot = plugin.getPlatformBridge().captureInventorySnapshot(event.getPlayer());
        } catch (Throwable throwable) {
            // On Folia the quit event may already run outside the player's owning region
            // thread; losing one quit snapshot is preferable to failing the handler.
            plugin.getPlatformBridge().warn("Skipped quit inventory snapshot: " + throwable.getMessage());
            return;
        }
        if (snapshot == null) {
            return;
        }
        runtime.getInventoryService()
                .persistSnapshot(snapshot)
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
