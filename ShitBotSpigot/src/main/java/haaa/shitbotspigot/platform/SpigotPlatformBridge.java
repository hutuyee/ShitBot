package haaa.shitbotspigot.platform;

import haaa.shitbot.core.platform.PlatformBridge;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

public final class SpigotPlatformBridge implements PlatformBridge {
    private final JavaPlugin plugin;

    public SpigotPlatformBridge(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public Path getDataDirectory() {
        return plugin.getDataFolder().toPath();
    }

    @Override
    public String getPlatformName() {
        return "Spigot";
    }

    @Override
    public CompletableFuture<Map<String, List<String>>> captureOnlinePlayers() {
        final CompletableFuture<Map<String, List<String>>> future = new CompletableFuture<Map<String, List<String>>>();
        Runnable capture = new Runnable() {
            @Override
            public void run() {
                try {
                    List<String> players = new ArrayList<String>();
                    for (Player player : Bukkit.getOnlinePlayers()) {
                        if (player != null) {
                            players.add(player.getName());
                        }
                    }
                    Map<String, List<String>> snapshot = new LinkedHashMap<String, List<String>>();
                    if (!players.isEmpty()) {
                        snapshot.put(Bukkit.getServer().getName(), players);
                    }
                    future.complete(snapshot);
                } catch (Throwable throwable) {
                    future.completeExceptionally(throwable);
                }
            }
        };
        if (Bukkit.isPrimaryThread()) {
            capture.run();
        } else {
            Bukkit.getScheduler().runTask(plugin, capture);
        }
        return future;
    }

    @Override
    public void executeOnPlatformThread(Runnable runnable) {
        if (runnable == null) {
            return;
        }
        if (Bukkit.isPrimaryThread()) {
            runnable.run();
        } else {
            Bukkit.getScheduler().runTask(plugin, runnable);
        }
    }

    @Override
    public void info(String message) {
        plugin.getLogger().info(message);
    }

    @Override
    public void warn(String message) {
        plugin.getLogger().warning(message);
    }

    @Override
    public void error(String message, Throwable throwable) {
        plugin.getLogger().log(Level.SEVERE, message, throwable);
    }
}
