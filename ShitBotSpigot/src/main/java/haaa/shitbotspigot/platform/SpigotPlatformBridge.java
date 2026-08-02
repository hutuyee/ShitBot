package haaa.shitbotspigot.platform;

import haaa.shitbot.core.chat.ChatPart;
import haaa.shitbot.core.platform.PlatformBridge;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
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
    public void broadcastMessage(final String message) {
        executeOnPlatformThread(new Runnable() {
            @Override
            public void run() {
                Bukkit.broadcastMessage(message == null ? "" : message);
            }
        });
    }

    @Override
    public void broadcastRichMessage(final List<ChatPart> parts) {
        executeOnPlatformThread(new Runnable() {
            @Override
            public void run() {
                List<BaseComponent> components = new ArrayList<BaseComponent>();
                if (parts != null) {
                    for (ChatPart part : parts) {
                        if (part == null || part.getText().isEmpty()) {
                            continue;
                        }
                        BaseComponent[] parsed = TextComponent.fromLegacyText(part.getText());
                        for (BaseComponent component : parsed) {
                            if (part.hasClickUrl()) {
                                component.setClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, part.getClickUrl()));
                                String hover = part.getHoverText().isEmpty() ? "点击打开" : part.getHoverText();
                                component.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                        TextComponent.fromLegacyText("§7" + hover)));
                            }
                            components.add(component);
                        }
                    }
                }
                BaseComponent[] output = components.toArray(new BaseComponent[components.size()]);
                Collection<? extends Player> onlinePlayers = Bukkit.getOnlinePlayers();
                for (Player player : onlinePlayers) {
                    player.spigot().sendMessage(output);
                }
            }
        });
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
