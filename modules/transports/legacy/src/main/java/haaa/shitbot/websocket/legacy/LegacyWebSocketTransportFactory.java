package haaa.shitbot.websocket.legacy;

import haaa.shitbot.core.onebot.transport.WebSocketTransport;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.drafts.Draft_6455;
import org.java_websocket.extensions.IExtension;
import org.java_websocket.handshake.ServerHandshake;

import java.io.IOException;
import java.net.Socket;
import java.net.URI;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Java 8 transport bundled only by Spigot and BungeeCord. */
public final class LegacyWebSocketTransportFactory implements WebSocketTransport.Factory {
    @Override
    public WebSocketTransport create(URI uri, Map<String, String> headers, int connectTimeoutMillis,
                                     int connectionLostTimeoutSeconds, int maximumMessageBytes,
                                     WebSocketTransport.Listener listener) {
        return new Client(uri, headers, connectTimeoutMillis, connectionLostTimeoutSeconds,
                maximumMessageBytes, listener);
    }

    private static final class Client extends WebSocketClient implements WebSocketTransport {
        private final WebSocketTransport.Listener listener;
        private final AtomicBoolean aborted = new AtomicBoolean();

        private Client(URI uri, Map<String, String> headers, int connectTimeoutMillis,
                       int connectionLostTimeoutSeconds, int maximumMessageBytes,
                       WebSocketTransport.Listener listener) {
            super(uri, new Draft_6455(Collections.<IExtension>emptyList(), maximumMessageBytes),
                    headers, connectTimeoutMillis);
            this.listener = listener;
            setConnectionLostTimeout(connectionLostTimeoutSeconds);
        }

        @Override
        public boolean connectBlocking(long timeout, TimeUnit unit) throws InterruptedException {
            return !aborted.get() && super.connectBlocking(timeout, unit) && !aborted.get();
        }

        @Override
        public CompletableFuture<Void> sendText(String message) {
            send(message);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void close(int code, String reason) {
            if (isOpen()) {
                super.close(code, reason);
            } else {
                abort(code, reason);
            }
        }

        @Override
        public void abort(int code, String reason) {
            aborted.set(true);
            try {
                closeConnection(code, reason);
            } finally {
                // A handshake can still be blocked before the library's writer thread exists.
                Socket socket = getSocket();
                if (socket != null) {
                    try {
                        socket.close();
                    } catch (IOException ignored) {
                    }
                }
            }
        }

        @Override
        public void onOpen(ServerHandshake handshake) {
            if (aborted.get()) {
                abort(1001, "connection cancelled");
            } else {
                listener.onOpen();
            }
        }

        @Override
        public void onMessage(String message) {
            listener.onText(message);
        }

        @Override
        public void onClose(int code, String reason, boolean remote) {
            listener.onClose(code, reason, remote);
        }

        @Override
        public void onError(Exception error) {
            listener.onError(error);
        }
    }
}
