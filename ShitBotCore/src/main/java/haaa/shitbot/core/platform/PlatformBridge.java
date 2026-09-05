package haaa.shitbot.core.platform;

import haaa.shitbot.core.chat.ChatPart;
import haaa.shitbot.core.console.ConsoleRequest;
import haaa.shitbot.core.console.ConsoleResult;
import haaa.shitbot.core.inventory.InventorySnapshot;

import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** Platform-specific operations required by the shared core. */
public interface PlatformBridge {
    Path getDataDirectory();

    String getPlatformName();

    CompletableFuture<Map<String, List<String>>> captureOnlinePlayers();

    /** Captures all online inventories in one platform-thread pass when supported. */
    default CompletableFuture<List<InventorySnapshot>> captureOnlineInventories() {
        return CompletableFuture.completedFuture(Collections.<InventorySnapshot>emptyList());
    }

    /** Captures only currently-online exact player names when supported. */
    default CompletableFuture<Map<String, InventorySnapshot>> captureInventories(List<String> playerNames) {
        return CompletableFuture.completedFuture(Collections.<String, InventorySnapshot>emptyMap());
    }

    void executeOnPlatformThread(Runnable runnable);

    default CompletableFuture<ConsoleResult> executeConsoleRequest(ConsoleRequest request) {
        return CompletableFuture.completedFuture(ConsoleResult.unavailable(
                request, "Console requests are unsupported on this platform.", getPlatformName()));
    }

    /** Polls a proxy-configured backend until it accepts a status ping. */
    default ServerAvailabilityWatch watchServerAvailability(String serverName,
                                                            int intervalSeconds,
                                                            Runnable availableAction) {
        warn("Current platform cannot watch backend server availability: " + serverName);
        return new ServerAvailabilityWatch() {
            @Override
            public void close() {
            }
        };
    }

    interface ServerAvailabilityWatch extends AutoCloseable {
        @Override
        void close();
    }

    void broadcastMessage(String message);

    /** Disconnects currently-online players whose names exactly match the supplied list. */
    void disconnectPlayers(List<String> playerNames, String reason);

    /** Broadcasts text with optional clickable URL parts. */
    default void broadcastRichMessage(List<ChatPart> parts) {
        StringBuilder builder = new StringBuilder();
        if (parts != null) {
            for (ChatPart part : parts) {
                if (part != null) {
                    builder.append(part.getText());
                }
            }
        }
        broadcastMessage(builder.toString());
    }

    void info(String message);

    void warn(String message);

    void error(String message, Throwable throwable);
}
