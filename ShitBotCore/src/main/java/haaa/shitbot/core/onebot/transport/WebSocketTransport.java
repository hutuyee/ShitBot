package haaa.shitbot.core.onebot.transport;

import java.net.URI;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Platform-selected WebSocket implementation; the shared core remains compatible with Java 8. */
public interface WebSocketTransport {
    boolean connectBlocking(long timeout, TimeUnit unit) throws Exception;

    boolean isOpen();

    boolean isClosed();

    /** Completes when the message has been accepted for sending, or reports an asynchronous send failure. */
    CompletableFuture<Void> sendText(String message);

    void close(int code, String reason);

    /** Releases the connection immediately, including an unfinished handshake. */
    void abort(int code, String reason);

    interface Listener {
        void onOpen();

        void onText(String message);

        void onClose(int code, String reason, boolean remote);

        void onError(Throwable error);
    }

    /** Each platform bundles exactly one provider through META-INF/services. */
    interface Factory {
        WebSocketTransport create(URI uri, Map<String, String> headers, int connectTimeoutMillis,
                                  int connectionLostTimeoutSeconds, int maximumMessageBytes, Listener listener);
    }
}
