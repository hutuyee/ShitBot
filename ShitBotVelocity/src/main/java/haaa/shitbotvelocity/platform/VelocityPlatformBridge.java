package haaa.shitbotvelocity.platform;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import haaa.shitbot.core.chat.ChatPart;
import haaa.shitbot.core.console.ConsoleRequest;
import haaa.shitbot.core.console.ConsoleResult;
import haaa.shitbot.core.console.ConsoleSettings;
import haaa.shitbot.core.platform.PlatformBridge;
import com.velocitypowered.api.proxy.messages.ChannelIdentifier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

public final class VelocityPlatformBridge implements PlatformBridge {
    private final ProxyServer server;
    private final Logger logger;
    private final Path dataDirectory;
    private final VelocityConsoleGateway consoleGateway;

    public VelocityPlatformBridge(Object plugin,
                                  ProxyServer server,
                                  Logger logger,
                                  Path dataDirectory,
                                  ChannelIdentifier consoleChannel) {
        this.server = server;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
        this.consoleGateway = new VelocityConsoleGateway(plugin, server, logger, consoleChannel);
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

    public VelocityConsoleGateway getConsoleGateway() {
        return consoleGateway;
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
        for (Player player : server.getAllPlayers()) {
            String serverName = player.getCurrentServer().isPresent()
                    ? player.getCurrentServer().get().getServerInfo().getName()
                    : "未连接子服";
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
                    String hover = part.getHoverText().isEmpty() ? "点击打开" : part.getHoverText();
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
