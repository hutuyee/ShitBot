package haaa.shitbotspigot.platform;

import haaa.shitbot.core.console.ConsoleRequest;
import haaa.shitbot.core.console.ConsoleResult;
import haaa.shitbot.core.console.ConsoleSettings;
import haaa.shitbot.core.console.ConsoleSocketProtocol;
import haaa.shitbot.core.util.NamedThreadFactory;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.Function;

final class SpigotConsoleSocketServer implements AutoCloseable {
    private final JavaPlugin plugin;
    private final ConsoleSettings.BackendListener settings;
    private final Function<ConsoleRequest, CompletableFuture<ConsoleResult>> executor;
    private final Consumer<String> canceller;
    private final ExecutorService acceptExecutor = Executors.newSingleThreadExecutor(
            new NamedThreadFactory("shitbot-console-accept", true));
    private final ExecutorService workerExecutor = new ThreadPoolExecutor(
            2, 2, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<Runnable>(32),
            new NamedThreadFactory("shitbot-console-worker", true));
    private final Map<String, Long> acceptedNonces = new ConcurrentHashMap<String, Long>();
    private final Map<String, Long> acceptedRequestIds = new ConcurrentHashMap<String, Long>();
    private volatile ServerSocket serverSocket;

    SpigotConsoleSocketServer(JavaPlugin plugin,
                              ConsoleSettings.BackendListener settings,
                              Function<ConsoleRequest, CompletableFuture<ConsoleResult>> executor,
                              Consumer<String> canceller) {
        this.plugin = plugin;
        this.settings = settings;
        this.executor = executor;
        this.canceller = canceller;
    }

    void start() throws IOException {
        if (settings.getToken().length() < 16) {
            throw new IOException("backend-transport.listener.token must contain at least 16 characters");
        }
        ServerSocket socket = new ServerSocket();
        socket.setReuseAddress(true);
        socket.bind(new InetSocketAddress(settings.getBindAddress(), settings.getPort()), 32);
        serverSocket = socket;
        acceptExecutor.execute(new Runnable() {
            @Override
            public void run() {
                acceptLoop();
            }
        });
        plugin.getLogger().info("ShitBot console listener started on "
                + settings.getBindAddress() + ':' + settings.getPort()
                + " as " + settings.getServerName() + '.');
    }

    private void acceptLoop() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                final Socket client = serverSocket.accept();
                final long acceptedAt = System.nanoTime();
                client.setSoTimeout(5000);
                try {
                    workerExecutor.execute(new Runnable() {
                        @Override
                        public void run() {
                            handle(client, acceptedAt);
                        }
                    });
                } catch (RejectedExecutionException exception) {
                    client.close();
                }
            } catch (SocketException exception) {
                if (serverSocket != null && !serverSocket.isClosed()) {
                    plugin.getLogger().warning("Console listener socket failed: " + exception.getMessage());
                }
                return;
            } catch (IOException exception) {
                plugin.getLogger().warning("Console listener accept failed: " + exception.getMessage());
            }
        }
    }

    private void handle(Socket client, long acceptedAt) {
        try {
            ConsoleSocketProtocol.AuthenticatedRequest authenticated =
                    ConsoleSocketProtocol.readRequest(client.getInputStream(), settings.getToken());
            if (acceptedNonces.putIfAbsent(authenticated.getNonceKey(),
                    Long.valueOf(authenticated.getTimestamp())) != null) {
                throw new IOException("Repeated console socket nonce");
            }
            cleanupNonces();
            ConsoleRequest request = authenticated.getRequest();
            long timeout = Math.max(request.getTimeoutSeconds(), request.getCaptureSeconds() + 5L);
            if (acceptedRequestIds.putIfAbsent(request.getRequestId(),
                    Long.valueOf(System.currentTimeMillis())) != null) {
                throw new IOException("Repeated console socket request ID");
            }
            long remainingNanos = TimeUnit.SECONDS.toNanos(timeout)
                    - (System.nanoTime() - acceptedAt);
            if (remainingNanos <= 0L) {
                throw new TimeoutException("Console socket request expired before execution");
            }
            CompletableFuture<ConsoleResult> execution = executor.apply(request);
            ConsoleResult result;
            try {
                result = execution.get(remainingNanos, TimeUnit.NANOSECONDS);
            } catch (TimeoutException exception) {
                execution.cancel(false);
                canceller.accept(request.getRequestId());
                throw exception;
            }
            ConsoleResult namedResult = new ConsoleResult(result.getRequestId(), result.getStatus(),
                    result.getOutput(), settings.getServerName());
            ConsoleSocketProtocol.writeResult(
                    client.getOutputStream(), settings.getToken(), authenticated.getNonce(), namedResult);
        } catch (Throwable throwable) {
            plugin.getLogger().warning("Console socket request rejected: " + safeMessage(throwable));
        } finally {
            try {
                client.close();
            } catch (IOException ignored) {
            }
        }
    }

    private void cleanupNonces() {
        if (acceptedNonces.size() < 4096 && acceptedRequestIds.size() < 4096) {
            return;
        }
        long cutoff = System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(2L);
        for (Map.Entry<String, Long> entry : acceptedNonces.entrySet()) {
            if (entry.getValue().longValue() < cutoff) {
                acceptedNonces.remove(entry.getKey(), entry.getValue());
            }
        }
        for (Map.Entry<String, Long> entry : acceptedRequestIds.entrySet()) {
            if (entry.getValue().longValue() < cutoff) {
                acceptedRequestIds.remove(entry.getKey(), entry.getValue());
            }
        }
    }

    private String safeMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    @Override
    public void close() {
        ServerSocket socket = serverSocket;
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
        acceptExecutor.shutdownNow();
        workerExecutor.shutdownNow();
        acceptedNonces.clear();
        acceptedRequestIds.clear();
    }
}
