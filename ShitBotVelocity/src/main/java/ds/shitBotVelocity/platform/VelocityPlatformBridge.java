package ds.shitBotVelocity.platform;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import haaa.shitbot.core.platform.PlatformBridge;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public final class VelocityPlatformBridge implements PlatformBridge {
    private final ProxyServer server;
    private final Logger logger;
    private final Path dataDirectory;

    public VelocityPlatformBridge(ProxyServer server, Logger logger, Path dataDirectory) {
        this.server = server;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
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
