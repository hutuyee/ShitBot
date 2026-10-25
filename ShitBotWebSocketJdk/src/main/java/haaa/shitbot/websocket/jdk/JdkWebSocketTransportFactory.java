package haaa.shitbot.websocket.jdk;

import haaa.shitbot.core.onebot.transport.WebSocketTransport;
import haaa.shitbot.core.util.FutureUtil;
import haaa.shitbot.core.util.NamedThreadFactory;

import java.io.IOException;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** JDK-only transport bundled by Velocity and Nukkit. */
public final class JdkWebSocketTransportFactory implements WebSocketTransport.Factory {
    // Reuse the HTTP client across reconnects, retaining the legacy transport's direct connections.
    private final HttpClient httpClient = HttpClient.newBuilder().proxy(new ProxySelector() {
        @Override
        public List<Proxy> select(URI uri) {
            return Collections.singletonList(Proxy.NO_PROXY);
        }

        @Override
        public void connectFailed(URI uri, SocketAddress address, IOException error) {
        }
    }).build();

    @Override
    public WebSocketTransport create(URI uri, Map<String, String> headers, int connectTimeoutMillis,
                                     int connectionLostTimeoutSeconds, int maximumMessageBytes,
                                     WebSocketTransport.Listener listener) {
        return new Client(httpClient, uri, headers, connectTimeoutMillis, connectionLostTimeoutSeconds,
                maximumMessageBytes, listener);
    }

    private static final class Client implements WebSocketTransport, WebSocket.Listener {
        private final HttpClient httpClient;
        private final URI uri;
        private final Map<String, String> headers;
        private final int connectTimeoutMillis;
        private final int pingIntervalSeconds;
        private final int maximumMessageBytes;
        private final WebSocketTransport.Listener listener;
        private final ScheduledExecutorService heartbeats = Executors.newSingleThreadScheduledExecutor(
                new NamedThreadFactory("shitbot-websocket-ping", true));
        private final AtomicBoolean started = new AtomicBoolean();
        private final AtomicBoolean closing = new AtomicBoolean();
        private final AtomicBoolean terminated = new AtomicBoolean();
        private final AtomicBoolean pingPending = new AtomicBoolean();
        private final AtomicLong lastPongNanos = new AtomicLong();
        private final CompletableFuture<Void> ready = new CompletableFuture<>();
        private final StringBuilder text = new StringBuilder();
        private volatile WebSocket socket;
        private volatile CompletableFuture<WebSocket> connecting;
        private CompletableFuture<Void> sendTail = CompletableFuture.completedFuture(null);
        // Receive callbacks are serialized by the JDK, including fragments of one message.
        private int textBytes;
        private int binaryBytes;
        private boolean previousHighSurrogate;

        private Client(HttpClient httpClient, URI uri, Map<String, String> headers, int connectTimeoutMillis,
                       int connectionLostTimeoutSeconds, int maximumMessageBytes,
                       WebSocketTransport.Listener listener) {
            this.httpClient = httpClient;
            this.uri = uri;
            this.headers = headers;
            this.connectTimeoutMillis = connectTimeoutMillis;
            this.pingIntervalSeconds = Math.max(1, connectionLostTimeoutSeconds);
            this.maximumMessageBytes = maximumMessageBytes;
            this.listener = listener;
        }

        @Override
        public boolean connectBlocking(long timeout, TimeUnit unit) throws Exception {
            if (!started.compareAndSet(false, true)) {
                throw new IllegalStateException("WebSocket connection has already been started");
            }
            if (closing.get() || terminated.get()) {
                return false;
            }
            try {
                WebSocket.Builder builder = httpClient.newWebSocketBuilder()
                        .connectTimeout(Duration.ofMillis(connectTimeoutMillis));
                headers.forEach(builder::header);
                CompletableFuture<WebSocket> attempt = builder.buildAsync(uri, this);
                connecting = attempt;
                attempt.whenComplete((webSocket, error) -> {
                    if (error != null) {
                        ready.completeExceptionally(FutureUtil.unwrap(error));
                    } else if (closing.get() || terminated.get()) {
                        webSocket.abort();
                    }
                });
                if (closing.get() || terminated.get()) {
                    attempt.cancel(true);
                    return false;
                }
                // Handshake completion and the onOpen callback need not be observed in the same order.
                ready.get(timeout, unit);
                return isOpen();
            } catch (TimeoutException error) {
                abort(1001, "connection timeout");
                return false;
            } catch (Exception error) {
                abort(1001, "connection failed");
                throw error;
            } finally {
                connecting = null;
            }
        }

        @Override
        public boolean isOpen() {
            WebSocket current = socket;
            return current != null && !closing.get() && !terminated.get()
                    && !current.isInputClosed() && !current.isOutputClosed();
        }

        @Override
        public boolean isClosed() {
            return terminated.get();
        }

        @Override
        public synchronized CompletableFuture<Void> sendText(String message) {
            if (!isOpen()) {
                return FutureUtil.failedFuture(new IllegalStateException("WebSocket is not connected"));
            }
            // The JDK rejects overlapping text sends; retain the ordering of OneBot actions.
            CompletableFuture<Void> sent = sendTail.thenCompose(ignored -> {
                if (!isOpen()) {
                    return FutureUtil.failedFuture(new IllegalStateException("WebSocket is not connected"));
                }
                return socket.sendText(message, true).thenApply(webSocket -> (Void) null);
            });
            sendTail = sent;
            sent.whenComplete((ignored, error) -> {
                if (error != null) {
                    fail(error);
                }
            });
            return sent;
        }

        @Override
        public void close(int code, String reason) {
            if (terminated.get() || !closing.compareAndSet(false, true)) {
                return;
            }
            heartbeats.shutdownNow();
            WebSocket current = socket;
            if (current == null) {
                abort(code, reason);
                return;
            }
            try {
                current.sendClose(code, reason).whenComplete((ignored, error) -> {
                    if (error != null) {
                        abort(code, reason);
                    }
                });
            } catch (RuntimeException error) {
                abort(code, reason);
            }
        }

        @Override
        public void abort(int code, String reason) {
            terminate(code, reason, false, null, true);
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            socket = webSocket;
            if (closing.get() || terminated.get()) {
                webSocket.abort();
                abort(1001, "connection closed before opening");
                return;
            }
            lastPongNanos.set(System.nanoTime());
            try {
                heartbeats.scheduleAtFixedRate(this::ping, pingIntervalSeconds,
                        pingIntervalSeconds, TimeUnit.SECONDS);
                listener.onOpen();
                if (!terminated.get()) {
                    webSocket.request(1);
                    ready.complete(null);
                }
            } catch (RuntimeException error) {
                fail(error);
            }
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            if (closing.get() || terminated.get()) {
                webSocket.request(1);
                return null;
            }
            // Count UTF-8 bytes without allocating another message or losing surrogate pairs at fragment boundaries.
            for (int index = 0; index < data.length(); index++) {
                char character = data.charAt(index);
                int bytes = character <= 0x7f ? 1 : character <= 0x7ff ? 2
                        : Character.isLowSurrogate(character) && previousHighSurrogate ? 1 : 3;
                if (textBytes > maximumMessageBytes - bytes) {
                    fail(new IOException("OneBot WebSocket text message exceeds its size limit"));
                    return null;
                }
                textBytes += bytes;
                previousHighSurrogate = Character.isHighSurrogate(character);
            }
            text.append(data);
            if (last) {
                String message = text.toString();
                text.setLength(0);
                textBytes = 0;
                previousHighSurrogate = false;
                listener.onText(message);
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
            if (binaryBytes > maximumMessageBytes - data.remaining()) {
                fail(new IOException("OneBot WebSocket binary message exceeds its size limit"));
                return null;
            }
            binaryBytes = last ? 0 : binaryBytes + data.remaining();
            // OneBot uses JSON text; consume binary messages without accumulating their payload.
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onPing(WebSocket webSocket, ByteBuffer message) {
            // The JDK sends the Pong response; each control frame still consumes one receive request.
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onPong(WebSocket webSocket, ByteBuffer message) {
            lastPongNanos.set(System.nanoTime());
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            boolean remote = !closing.get();
            terminate(statusCode, reason, remote, null, false);
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            fail(error);
        }

        private void ping() {
            if (!isOpen()) {
                return;
            }
            long interval = TimeUnit.SECONDS.toNanos(pingIntervalSeconds);
            if (System.nanoTime() - lastPongNanos.get() > interval + interval / 2) {
                abort(1006, "WebSocket pong timeout");
                return;
            }
            if (!pingPending.compareAndSet(false, true)) {
                return;
            }
            try {
                socket.sendPing(ByteBuffer.allocate(0)).whenComplete((ignored, error) -> {
                    pingPending.set(false);
                    if (error != null) {
                        fail(error);
                    }
                });
            } catch (RuntimeException error) {
                pingPending.set(false);
                fail(error);
            }
        }

        private void fail(Throwable error) {
            terminate(1006, "WebSocket transport failed", false, FutureUtil.unwrap(error), true);
        }

        private void terminate(int code, String reason, boolean remote, Throwable error, boolean abortSocket) {
            if (!terminated.compareAndSet(false, true)) {
                return;
            }
            closing.set(true);
            heartbeats.shutdownNow();
            ready.completeExceptionally(error == null
                    ? new IOException("WebSocket closed: " + reason) : error);
            if (abortSocket) {
                CompletableFuture<WebSocket> attempt = connecting;
                if (attempt != null) {
                    attempt.cancel(true);
                }
                WebSocket current = socket;
                if (current != null) {
                    current.abort();
                }
            }
            try {
                if (error != null) {
                    listener.onError(error);
                }
            } finally {
                listener.onClose(code, reason, remote);
            }
        }
    }
}
