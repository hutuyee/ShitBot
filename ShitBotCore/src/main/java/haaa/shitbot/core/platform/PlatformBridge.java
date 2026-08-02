package haaa.shitbot.core.platform;

import haaa.shitbot.core.chat.ChatPart;

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
