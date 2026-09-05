package haaa.shitbotvelocity.platform;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.scheduler.ScheduledTask;
import haaa.shitbot.core.chat.ChatPart;
import haaa.shitbot.core.config.Translations;
import haaa.shitbot.core.console.ConsoleRequest;
import haaa.shitbot.core.console.ConsoleResult;
import haaa.shitbot.core.console.ConsoleSettings;
import haaa.shitbot.core.platform.PlatformBridge;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.slf4j.Logger;
import haaa.shitbotvelocity.ShitBotVelocity;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class VelocityPlatformBridge implements PlatformBridge {
    private final ShitBotVelocity plugin;
    private final ProxyServer server;
    private final Logger logger;
    private final Path dataDirectory;
    private final VelocityConsoleGateway consoleGateway;

    public VelocityPlatformBridge(ShitBotVelocity plugin,
                                  ProxyServer server,
                                  Logger logger,
                                  Path dataDirectory) {
        this.plugin = plugin;
        this.server = server;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
        this.consoleGateway = new VelocityConsoleGateway(plugin, server, dataDirectory);
    }

    @Override
    public Path getDataDirectory() {
        return dataDirectory;
    }

    @Override
    public String getPlatformName() {
        return "Velocity";
    }

    @Override
    public CompletableFuture<ConsoleResult> executeConsoleRequest(ConsoleRequest request) {
        return consoleGateway.execute(request);
    }

    public void configureConsole(ConsoleSettings settings) {
        consoleGateway.configure(settings);
    }

    @Override
    public ServerAvailabilityWatch watchServerAvailability(String serverName,
                                                           int intervalSeconds,
                                                           Runnable availableAction) {
        java.util.Optional<RegisteredServer> found = server.getServer(serverName);
        if (!found.isPresent()) {
            for (RegisteredServer registered : server.getAllServers()) {
                if (registered.getServerInfo().getName().equalsIgnoreCase(serverName.trim())) {
                    found = java.util.Optional.of(registered);
                    break;
                }
            }
        }
        if (!found.isPresent()) {
            warn("Startup notice target is not configured in Velocity: " + serverName);
            return noOpWatch();
        }
        final RegisteredServer target = found.get();
        final AtomicBoolean closed = new AtomicBoolean();
        final AtomicBoolean checking = new AtomicBoolean();
        final AtomicReference<ScheduledTask> taskReference = new AtomicReference<ScheduledTask>();
        Runnable poll = new Runnable() {
            @Override
            public void run() {
                if (closed.get() || !checking.compareAndSet(false, true)) {
                    return;
                }
                target.ping().whenComplete((response, throwable) -> {
                    checking.set(false);
                    if (closed.get() || throwable != null || response == null
                            || !closed.compareAndSet(false, true)) {
                        return;
                    }
                    ScheduledTask task = taskReference.get();
                    if (task != null) {
                        task.cancel();
                    }
                    availableAction.run();
                });
            }
        };
        ScheduledTask task = server.getScheduler().buildTask(plugin, poll)
                .repeat(Duration.ofSeconds(Math.max(1, intervalSeconds)))
                .schedule();
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
        for (Player player : server.getAllPlayers()) {
            String serverName = player.getCurrentServer().isPresent()
                    ? player.getCurrentServer().get().getServerInfo().getName()
                    : (plugin.getTranslations() == null
                            ? "No backend"
                            : plugin.getTranslations().get("image.no-backend"));
            List<String> players = snapshot.get(serverName);
            if (players == null) {
                players = new ArrayList<String>();
                snapshot.put(serverName, players);
            }
            players.add(player.getUsername());
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
        Component component = LegacyComponentSerializer.legacySection().deserialize(message == null ? "" : message);
        for (Player player : server.getAllPlayers()) {
            player.sendMessage(component);
        }
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
        Component component = LegacyComponentSerializer.legacySection().deserialize(reason == null ? "" : reason);
        for (Player player : server.getAllPlayers()) {
            if (player != null && exactNames.contains(player.getUsername())) {
                player.disconnect(component);
            }
        }
    }

    @Override
    public void broadcastRichMessage(List<ChatPart> parts) {
        Component output = Component.empty();
        if (parts != null) {
            for (ChatPart part : parts) {
                if (part == null || part.getText().isEmpty()) {
                    continue;
                }
                Component component = LegacyComponentSerializer.legacySection().deserialize(part.getText());
                if (part.hasClickUrl()) {
                    component = component.clickEvent(ClickEvent.openUrl(part.getClickUrl()));
                    Translations translations = plugin.getTranslations();
                    String hover = part.getHoverText().isEmpty()
                            ? (translations == null ? "Click to open" : translations.get("media.open-hover"))
                            : part.getHoverText();
                    component = component.hoverEvent(HoverEvent.showText(Component.text(hover)));
                }
                output = output.append(component);
            }
        }
        for (Player player : server.getAllPlayers()) {
            player.sendMessage(output);
        }
    }

    @Override
    public void info(String message) {
        logger.info(message);
    }

    @Override
    public void warn(String message) {
        logger.warn(message);
    }

    @Override
    public void error(String message, Throwable throwable) {
        logger.error(message, throwable);
    }
}
