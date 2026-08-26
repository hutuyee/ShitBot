package haaa.shitbotspigot.platform;

import haaa.shitbot.core.console.ConsoleRequest;
import haaa.shitbot.core.console.ConsoleResult;
import haaa.shitbot.core.console.ConsoleSettings;
import haaa.shitbot.core.console.ConsoleSocketProtocol;
import haaa.shitbot.core.console.ConsoleTlsSupport;
import haaa.shitbot.core.util.NamedThreadFactory;
import haaa.shitbot.core.util.NetworkUtil;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
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
    private final Map<String, FailureLogState> rejectionLogStates =
            new ConcurrentHashMap<String, FailureLogState>();
    private final AtomicLong lastAddressRejectionLog = new AtomicLong();
    private volatile Set<InetAddress> allowedProxyAddresses = Collections.emptySet();
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
        if (!settings.isTlsEnabled()
                && !NetworkUtil.isLoopbackHost(settings.getBindAddress())
                && !settings.isAllowInsecureRemotePlaintext()) {
            throw new IOException("Remote console listener requires TLS; plaintext was not explicitly allowed");
        }
        allowedProxyAddresses = resolveAllowedProxyAddresses();
        ServerSocket socket = settings.isTlsEnabled()
                ? ConsoleTlsSupport.createServerSocket(settings, plugin.getDataFolder().toPath())
                : new ServerSocket();
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
                + " as " + settings.getServerName()
                + (settings.isTlsEnabled() ? " with TLS." : "."));
    }

    private void acceptLoop() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                final Socket client = serverSocket.accept();
                final long acceptedAt = System.nanoTime();
                if (!allowedProxyAddresses.contains(client.getInetAddress())) {
                    logRejectedAddress(client.getInetAddress());
                    client.close();
                    continue;
                }
                client.setSoTimeout(settings.getAuthenticationTimeoutMillis());
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

    private Set<InetAddress> resolveAllowedProxyAddresses() throws IOException {
        Set<InetAddress> resolved = new HashSet<InetAddress>();
        for (String configured : settings.getAllowedProxyAddresses()) {
            for (InetAddress address : InetAddress.getAllByName(configured)) {
                resolved.add(address);
            }
        }
        if (resolved.isEmpty()) {
            throw new IOException("backend-transport.listener.allowed-proxy-addresses must not be empty");
        }
        return Collections.unmodifiableSet(resolved);
    }

    private void logRejectedAddress(InetAddress address) {
        long now = System.currentTimeMillis();
        long previous = lastAddressRejectionLog.get();
        if (now - previous >= TimeUnit.SECONDS.toMillis(10L)
                && lastAddressRejectionLog.compareAndSet(previous, now)) {
            plugin.getLogger().warning("Console listener rejected non-whitelisted address: "
                    + address.getHostAddress());
        }
    }

    ConsoleSettings.BackendListener getSettings() {
        return settings;
    }

    boolean matches(ConsoleSettings.BackendListener candidate) {
        return candidate != null
                && settings.getBindAddress().equalsIgnoreCase(candidate.getBindAddress())
                && settings.getPort() == candidate.getPort()
                && settings.getToken().equals(candidate.getToken())
                && settings.getServerName().equals(candidate.getServerName())
                && settings.getAuthenticationTimeoutMillis() == candidate.getAuthenticationTimeoutMillis()
                && settings.getAllowedProxyAddresses().equals(candidate.getAllowedProxyAddresses())
                && settings.isAllowInsecureRemotePlaintext()
                == candidate.isAllowInsecureRemotePlaintext()
                && settings.isTlsEnabled() == candidate.isTlsEnabled()
                && settings.getTlsKeyStore().equals(candidate.getTlsKeyStore())
                && settings.getTlsKeyStorePassword().equals(candidate.getTlsKeyStorePassword())
                && settings.getTlsTrustStore().equals(candidate.getTlsTrustStore())
                && settings.getTlsTrustStorePassword().equals(candidate.getTlsTrustStorePassword())
                && settings.isTlsRequireClientCertificate()
                == candidate.isTlsRequireClientCertificate();
    }

    boolean usesSamePort(ConsoleSettings.BackendListener candidate) {
        return candidate != null && settings.getPort() == candidate.getPort();
    }

    private void handle(Socket client, long acceptedAt) {
        try {
            if (client instanceof javax.net.ssl.SSLSocket) {
                ((javax.net.ssl.SSLSocket) client).startHandshake();
            }
            byte[] challenge = ConsoleSocketProtocol.writeChallenge(client.getOutputStream());
            ConsoleSocketProtocol.AuthenticatedRequest authenticated =
                    ConsoleSocketProtocol.readRequest(
                            client.getInputStream(), settings.getToken(), challenge);
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
                    client.getOutputStream(), settings.getToken(), challenge,
                    authenticated.getNonce(), namedResult);
        } catch (Throwable throwable) {
            logRejectedRequest(client.getInetAddress(), throwable);
        } finally {
            try {
                client.close();
            } catch (IOException ignored) {
            }
        }
    }

    private void logRejectedRequest(InetAddress address, Throwable throwable) {
        Throwable cause = rootCause(throwable);
        String addressText = address == null ? "unknown" : address.getHostAddress();
        String key = addressText + ':' + cause.getClass().getName();
        FailureLogState created = new FailureLogState();
        FailureLogState state = rejectionLogStates.putIfAbsent(key, created);
        if (state == null) {
            state = created;
        }
        long now = System.currentTimeMillis();
        int suppressed;
        synchronized (state) {
            if (now - state.lastLoggedAt < TimeUnit.SECONDS.toMillis(10L)) {
                state.suppressed++;
                return;
            }
            suppressed = state.suppressed;
            state.suppressed = 0;
            state.lastLoggedAt = now;
        }
        plugin.getLogger().warning("Console socket request rejected from " + addressText + ": "
                + safeMessage(cause)
                + (suppressed <= 0 ? "" : " (suppressed " + suppressed + " similar failures)"));
    }

    private Throwable rootCause(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
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
        Throwable current = rootCause(throwable);
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
        rejectionLogStates.clear();
        allowedProxyAddresses = Collections.emptySet();
    }

    private static final class FailureLogState {
        private long lastLoggedAt;
        private int suppressed;
    }
}
