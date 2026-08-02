package haaa.shitbot.core.platform;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** Platform-specific operations required by the shared core. */
public interface PlatformBridge {
    Path getDataDirectory();

    String getPlatformName();

    CompletableFuture<Map<String, List<String>>> captureOnlinePlayers();

    void executeOnPlatformThread(Runnable runnable);

    void broadcastMessage(String message);

    void info(String message);

    void warn(String message);

    void error(String message, Throwable throwable);
}
