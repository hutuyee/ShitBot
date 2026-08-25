package haaa.shitbotbungee.platform;

import haaa.shitbot.core.chat.ChatPart;
import haaa.shitbot.core.console.ConsoleRequest;
import haaa.shitbot.core.console.ConsoleResult;
import haaa.shitbot.core.console.ConsoleSettings;
import haaa.shitbot.core.platform.PlatformBridge;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.config.ServerInfo;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.plugin.Plugin;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

public final class BungeePlatformBridge implements PlatformBridge {
    private final Plugin plugin;
    private final BungeeConsoleGateway consoleGateway;

    public BungeePlatformBridge(Plugin plugin) {
        this.plugin = plugin;
        this.consoleGateway = new BungeeConsoleGateway(plugin);
    }

    @Override
    public Path getDataDirectory() {
        return plugin.getDataFolder().toPath();
    }

    @Override
    public String getPlatformName() {
        return "BungeeCord";
    }

    @Override
    public CompletableFuture<ConsoleResult> executeConsoleRequest(ConsoleRequest request) {
        return consoleGateway.execute(request);
    }

    public void configureConsole(ConsoleSettings settings) {
        consoleGateway.configure(settings);
    }

    public void close() {
        consoleGateway.close();
    }

    @Override
    public CompletableFuture<Map<String, List<String>>> captureOnlinePlayers() {
        Map<String, List<String>> snapshot = new LinkedHashMap<String, List<String>>();
        for (Map.Entry<String, ServerInfo> entry : ProxyServer.getInstance().getServers().entrySet()) {
            List<String> players = new ArrayList<String>();
            for (ProxiedPlayer player : entry.getValue().getPlayers()) {
                if (player != null) {
                    players.add(player.getName());
                }
            }
            if (!players.isEmpty()) {
                snapshot.put(entry.getKey(), players);
            }
        }
        return CompletableFuture.completedFuture(snapshot);
    }

    @Override
    public void executeOnPlatformThread(Runnable runnable) {
        if (runnable != null) {
            runnable.run();
        }
    }

    @Override
    public void broadcastMessage(String message) {
        ProxyServer.getInstance().broadcast(TextComponent.fromLegacyText(message == null ? "" : message));
    }

    @Override
    public void disconnectPlayers(List<String> playerNames, String reason) {
        if (playerNames == null || playerNames.isEmpty()) {
            return;
        }
        Set<String> exactNames = new HashSet<String>();
        for (String playerName : playerNames) {
            if (playerName != null && !playerName.trim().isEmpty()) {
                exactNames.add(playerName.trim());
            }
        }
        if (exactNames.isEmpty()) {
            return;
        }
        BaseComponent[] components = TextComponent.fromLegacyText(reason == null ? "" : reason);
        for (ProxiedPlayer player : ProxyServer.getInstance().getPlayers()) {
            if (player != null && exactNames.contains(player.getName())) {
                player.disconnect(components);
            }
        }
    }

    @Override
    public void broadcastRichMessage(List<ChatPart> parts) {
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
        ProxyServer.getInstance().broadcast(components.toArray(new BaseComponent[components.size()]));
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
        plugin.getLogger().severe(message + (throwable == null ? "" : ": " + throwable.getMessage()));
        if (throwable != null) {
            throwable.printStackTrace();
        }
    }
}
