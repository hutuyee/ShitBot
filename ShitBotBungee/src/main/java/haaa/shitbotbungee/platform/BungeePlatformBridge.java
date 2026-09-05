package haaa.shitbotbungee.platform;

import haaa.shitbot.core.chat.ChatPart;
import haaa.shitbot.core.config.Translations;
import haaa.shitbot.core.console.ConsoleRequest;
import haaa.shitbot.core.console.ConsoleResult;
import haaa.shitbot.core.console.ConsoleSettings;
import haaa.shitbot.core.platform.PlatformBridge;
import haaa.shitbot.core.update.UpdateInfo;
import net.md_5.bungee.api.Callback;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.ServerPing;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.config.ServerInfo;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.plugin.Plugin;
import haaa.shitbotbungee.ShitBotBungee;
import net.md_5.bungee.api.scheduler.ScheduledTask;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class BungeePlatformBridge implements PlatformBridge {
    private final ShitBotBungee plugin;
    private final BungeeConsoleGateway consoleGateway;

    public BungeePlatformBridge(ShitBotBungee plugin) {
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

    public CompletableFuture<List<ConsoleResult>> updateAllBackends(UpdateInfo release) {
        return consoleGateway.updateAllBackends(release);
    }

    @Override
    public ServerAvailabilityWatch watchServerAvailability(String serverName,
                                                           int intervalSeconds,
                                                           Runnable availableAction) {
        final ServerInfo target = findServer(serverName);
        if (target == null) {
            warn("Startup notice target is not configured in BungeeCord: " + serverName);
            return noOpWatch();
        }
        final AtomicBoolean closed = new AtomicBoolean();
        final AtomicBoolean checking = new AtomicBoolean();
        final AtomicReference<ScheduledTask> taskReference = new AtomicReference<ScheduledTask>();
        Runnable poll = new Runnable() {
            @Override
            public void run() {
                if (closed.get() || !checking.compareAndSet(false, true)) {
                    return;
                }
                target.ping(new Callback<ServerPing>() {
                    @Override
                    public void done(ServerPing result, Throwable error) {
                        checking.set(false);
                        if (closed.get() || error != null || result == null
                                || !closed.compareAndSet(false, true)) {
                            return;
                        }
                        ScheduledTask task = taskReference.get();
                        if (task != null) {
                            task.cancel();
                        }
                        availableAction.run();
                    }
                });
            }
        };
        ScheduledTask task = ProxyServer.getInstance().getScheduler().schedule(
                plugin, poll, 0L, Math.max(1, intervalSeconds), TimeUnit.SECONDS);
        taskReference.set(task);
        if (closed.get()) {
            task.cancel();
        }
        return new ServerAvailabilityWatch() {
            @Override
            public void close() {
                if (closed.compareAndSet(false, true)) {
                    ScheduledTask task = taskReference.get();
                    if (task != null) {
                        task.cancel();
                    }
                }
            }
        };
    }

    private ServerInfo findServer(String serverName) {
        if (serverName == null) {
            return null;
        }
        for (Map.Entry<String, ServerInfo> entry : ProxyServer.getInstance().getServers().entrySet()) {
            if (entry.getKey().equalsIgnoreCase(serverName.trim())) {
                return entry.getValue();
            }
        }
        return null;
    }

    private ServerAvailabilityWatch noOpWatch() {
        return new ServerAvailabilityWatch() {
            @Override
            public void close() {
            }
        };
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
                        Translations translations = plugin.getTranslations();
                        String hover = part.getHoverText().isEmpty()
                                ? (translations == null ? "Click to open" : translations.get("media.open-hover"))
                                : part.getHoverText();
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
