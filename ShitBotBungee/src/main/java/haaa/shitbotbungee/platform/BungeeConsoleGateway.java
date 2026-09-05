package haaa.shitbotbungee.platform;

import haaa.shitbot.core.config.Translations;
import haaa.shitbot.core.console.ConsoleRequest;
import haaa.shitbot.core.console.ConsoleResult;
import haaa.shitbot.core.console.ConsoleSettings;
import haaa.shitbot.core.console.ConsoleSocketProtocol;
import haaa.shitbot.core.console.LatestLogCapture;
import haaa.shitbot.core.console.LuckPermsPermissionResolver;
import haaa.shitbot.core.update.ReleaseAsset;
import haaa.shitbot.core.update.UpdateInfo;
import haaa.shitbot.core.update.UpdatePlatform;
import haaa.shitbot.core.util.FutureUtil;
import haaa.shitbot.core.util.NamedThreadFactory;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.plugin.Plugin;
import haaa.shitbotbungee.ShitBotBungee;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicBoolean;

public final class BungeeConsoleGateway implements AutoCloseable {
    private final ShitBotBungee plugin;
    private final AtomicBoolean localCommandRunning = new AtomicBoolean();
    private volatile CompletableFuture<ConsoleResult> activeLocalFuture;
    private volatile ConsoleRequest activeLocalRequest;
    private final ThreadPoolExecutor socketExecutor = new ThreadPoolExecutor(
            2, 2, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<Runnable>(32),
            new NamedThreadFactory("shitbot-bungee-console", true),
            new ThreadPoolExecutor.AbortPolicy());
    private final Map<String, EndpointCircuit> endpointCircuits =
            new ConcurrentHashMap<String, EndpointCircuit>();
    private volatile ConsoleSettings.BackendTransport backendTransport;

    public BungeeConsoleGateway(ShitBotBungee plugin) {
        this.plugin = plugin;
    }

    public void configure(ConsoleSettings settings) {
        backendTransport = settings == null ? null : settings.getBackendTransport();
    }

    public CompletableFuture<ConsoleResult> execute(ConsoleRequest request) {
        if (request.getTarget() == ConsoleSettings.Target.PROXY
                && request.getOperation() == ConsoleRequest.Operation.COMMAND) {
            return executeLocally(request);
        }
        return sendToBackend(request);
    }

    public CompletableFuture<java.util.List<ConsoleResult>> updateAllBackends(UpdateInfo release) {
        final ConsoleSettings.BackendTransport transport = backendTransport;
        if (transport == null || transport.getEndpoints().isEmpty()) {
            return CompletableFuture.completedFuture(
                    java.util.Collections.<ConsoleResult>emptyList());
        }
        if (release == null) {
            return FutureUtil.failedFuture(new IllegalArgumentException(
                    text("admin.update.release-metadata-missing")));
        }
        ReleaseAsset jarAsset = release.findJarAsset(UpdatePlatform.SPIGOT);
        if (jarAsset == null) {
            return FutureUtil.failedFuture(new java.io.IOException(
                    text("admin.update.backend-jar-missing")));
        }
        ReleaseAsset checksumAsset = release.findAsset(jarAsset.getName() + ".sha256");
        if (checksumAsset == null) {
            return FutureUtil.failedFuture(new java.io.IOException(
                    format("admin.update.checksum-missing", "%asset%",
                            jarAsset.getName() + ".sha256")));
        }
        ReleaseAsset signatureAsset = release.findAsset(jarAsset.getName() + ".sig");
        if (signatureAsset == null) {
            return FutureUtil.failedFuture(new java.io.IOException(
                    format("admin.update.signature-missing", "%asset%",
                            jarAsset.getName() + ".sig")));
        }

        final java.util.List<CompletableFuture<ConsoleResult>> requests =
                new java.util.ArrayList<CompletableFuture<ConsoleResult>>();
        for (ConsoleSettings.BackendEndpoint endpoint : transport.getEndpoints().values()) {
            ConsoleRequest request = ConsoleRequest.update(
                    endpoint.getName(), release, jarAsset, checksumAsset, signatureAsset);
            requests.add(sendToSocket(request, endpoint));
        }
        CompletableFuture<?>[] array = requests.toArray(new CompletableFuture<?>[requests.size()]);
        return CompletableFuture.allOf(array).thenApply(
                new java.util.function.Function<Void, java.util.List<ConsoleResult>>() {
                    @Override
                    public java.util.List<ConsoleResult> apply(Void ignored) {
                        java.util.List<ConsoleResult> results =
                                new java.util.ArrayList<ConsoleResult>(requests.size());
                        for (CompletableFuture<ConsoleResult> request : requests) {
                            results.add(request.join());
                        }
                        return results;
                    }
                });
    }

    private CompletableFuture<ConsoleResult> sendToBackend(final ConsoleRequest request) {
        final ConsoleSettings.BackendEndpoint endpoint = selectEndpoint(request);
        if (endpoint != null) {
            return sendToSocket(request, endpoint);
        }
        return CompletableFuture.completedFuture(ConsoleResult.unavailable(
                request, text("console.result.insecure-fallback-refused"),
                "BungeeCord"));
    }

    private CompletableFuture<ConsoleResult> sendToSocket(
            final ConsoleRequest request,
            final ConsoleSettings.BackendEndpoint endpoint) {
        final ConsoleSettings.BackendTransport transport = backendTransport;
        final CompletableFuture<ConsoleResult> result = new CompletableFuture<ConsoleResult>();
        final long deadlineNanos = System.nanoTime()
                + TimeUnit.SECONDS.toNanos(request.getTimeoutSeconds());
        try {
            socketExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    if (System.nanoTime() >= deadlineNanos) {
                        result.complete(ConsoleResult.unavailable(
                                request, text("console.result.expired-before-connect"), endpoint.getName()));
                        return;
                    }
                    EndpointCircuit circuit = endpointCircuit(endpoint);
                    if (circuit.isOpen(System.nanoTime())) {
                        result.complete(ConsoleResult.unavailable(
                                request, text("console.result.circuit-open"), endpoint.getName()));
                        return;
                    }
                    try {
                        ConsoleResult response = ConsoleSocketProtocol.exchange(
                                endpoint, transport, request, plugin.getDataFolder().toPath(), deadlineNanos);
                        circuit.recordSuccess();
                        result.complete(response);
                    } catch (Exception exception) {
                        circuit.recordFailure(System.nanoTime());
                        result.complete(ConsoleResult.unavailable(request,
                                format("console.result.connect-failed",
                                        "%error%", safeMessage(exception)), endpoint.getName()));
                    }
                }
            });
        } catch (RejectedExecutionException exception) {
            result.complete(ConsoleResult.unavailable(
                    request, text("console.result.queue-full"), endpoint.getName()));
        }
        return result;
    }

    private EndpointCircuit endpointCircuit(ConsoleSettings.BackendEndpoint endpoint) {
        String key = endpoint.getName().toLowerCase(java.util.Locale.ROOT);
        EndpointCircuit created = new EndpointCircuit();
        EndpointCircuit existing = endpointCircuits.putIfAbsent(key, created);
        return existing == null ? created : existing;
    }

    private String safeMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.trim().isEmpty()
                ? current.getClass().getSimpleName() : message;
    }

    private static final class EndpointCircuit {
        private int consecutiveFailures;
        private long openUntilNanos;

        private synchronized boolean isOpen(long nowNanos) {
            return nowNanos < openUntilNanos;
        }

        private synchronized void recordSuccess() {
            consecutiveFailures = 0;
            openUntilNanos = 0L;
        }

        private synchronized void recordFailure(long nowNanos) {
            consecutiveFailures++;
            if (consecutiveFailures >= 3) {
                openUntilNanos = nowNanos + TimeUnit.SECONDS.toNanos(5L);
                consecutiveFailures = 0;
            }
        }
    }

    private ConsoleSettings.BackendEndpoint selectEndpoint(ConsoleRequest request) {
        ConsoleSettings.BackendTransport transport = backendTransport;
        if (transport == null) {
            return null;
        }
        if (!request.getServer().isEmpty()) {
            return transport.getEndpoint(request.getServer());
        }
        for (String playerName : request.getPlayerNames()) {
            ProxiedPlayer player = plugin.getProxy().getPlayer(playerName);
            if (player != null && player.getServer() != null) {
                ConsoleSettings.BackendEndpoint endpoint =
                        transport.getEndpoint(player.getServer().getInfo().getName());
                if (endpoint != null) {
                    return endpoint;
                }
            }
        }
        ConsoleSettings.BackendEndpoint defaultEndpoint =
                transport.getEndpoint(transport.getDefaultServer());
        if (!transport.getDefaultServer().isEmpty()) {
            return defaultEndpoint;
        }
        return transport.getOnlyEndpoint();
    }

    private CompletableFuture<ConsoleResult> executeLocally(final ConsoleRequest request) {
        final Object gate = new Object();
        final AtomicBoolean commandDispatched = new AtomicBoolean();
        final CompletableFuture<ConsoleResult> result = new CompletableFuture<ConsoleResult>();
        hasPermission(request).whenComplete(new java.util.function.BiConsumer<Boolean, Throwable>() {
            @Override
            public void accept(Boolean allowed, Throwable throwable) {
                final CompletableFuture<ConsoleResult> execution;
                synchronized (gate) {
                    if (result.isDone()) {
                        return;
                    }
                    if (throwable != null || !Boolean.TRUE.equals(allowed)) {
                        result.complete(new ConsoleResult(
                                request.getRequestId(), ConsoleResult.Status.NO_PERMISSION,
                                format("console.result.permission-denied",
                                        "%permission%", request.getPermission()), "BungeeCord"));
                        return;
                    }
                    execution = executeAuthorizedLocal(request, commandDispatched);
                }
                execution.whenComplete(new java.util.function.BiConsumer<ConsoleResult, Throwable>() {
                    @Override
                    public void accept(ConsoleResult value, Throwable failure) {
                        if (failure == null) {
                            result.complete(value);
                        } else {
                            result.completeExceptionally(failure);
                        }
                    }
                });
            }
        });
        long timeout = Math.max(request.getTimeoutSeconds(), request.getCaptureSeconds() + 5L);
        plugin.getProxy().getScheduler().schedule(plugin, new Runnable() {
            @Override
            public void run() {
                synchronized (gate) {
                    if (commandDispatched.get()) {
                        result.complete(new ConsoleResult(request.getRequestId(),
                                ConsoleResult.Status.RESULT_TIMEOUT,
                                text("console.result.result-timeout"),
                                "BungeeCord"));
                    } else {
                        result.complete(ConsoleResult.unavailable(
                                request, text("console.result.request-timeout"), "BungeeCord"));
                    }
                }
            }
        }, timeout, TimeUnit.SECONDS);
        return result;
    }

    private CompletableFuture<ConsoleResult> executeAuthorizedLocal(final ConsoleRequest request,
                                                                     AtomicBoolean commandDispatched) {
        if (!localCommandRunning.compareAndSet(false, true)) {
            return CompletableFuture.completedFuture(ConsoleResult.unavailable(
                    request, text("console.result.command-running"), "BungeeCord"));
        }
        final CompletableFuture<ConsoleResult> future = new CompletableFuture<ConsoleResult>();
        final LatestLogCapture capture = LatestLogCapture.begin(privacyPlayerNames(request));
        activeLocalFuture = future;
        activeLocalRequest = request;
        final boolean accepted;
        try {
            commandDispatched.set(true);
            accepted = plugin.getProxy().getPluginManager().dispatchCommand(
                    plugin.getProxy().getConsole(), request.getCommand());
        } catch (Throwable throwable) {
            clearLocalCapture(future);
            localCommandRunning.set(false);
            future.complete(new ConsoleResult(request.getRequestId(), ConsoleResult.Status.FAILED,
                    throwable.getMessage(), "BungeeCord"));
            return future;
        }
        plugin.getProxy().getScheduler().schedule(plugin, new Runnable() {
            @Override
            public void run() {
                clearLocalCapture(future);
                String output;
                try {
                    output = capture.readNewContent();
                } catch (IOException exception) {
                    output = format("console.result.log-read-failed",
                            "%error%", safeMessage(exception));
                }
                if (output.isEmpty()) {
                    output = accepted
                            ? text("console.result.no-new-log")
                            : text("console.result.command-rejected");
                }
                future.complete(new ConsoleResult(request.getRequestId(),
                        accepted ? ConsoleResult.Status.SUCCESS : ConsoleResult.Status.FAILED,
                        output, "BungeeCord"));
                localCommandRunning.set(false);
            }
        }, Math.max(1, request.getCaptureSeconds()), TimeUnit.SECONDS);
        return future;
    }

    private List<String> privacyPlayerNames(ConsoleRequest request) {
        List<String> playerNames = new ArrayList<String>(request.getPlayerNames());
        for (ProxiedPlayer player : plugin.getProxy().getPlayers()) {
            if (player != null && player.getName() != null) {
                playerNames.add(player.getName());
            }
        }
        return playerNames;
    }

    private CompletableFuture<Boolean> hasPermission(ConsoleRequest request) {
        if (request.getPermission().isEmpty()) {
            return CompletableFuture.completedFuture(Boolean.TRUE);
        }
        for (String playerName : request.getPlayerNames()) {
            ProxiedPlayer player = plugin.getProxy().getPlayer(playerName);
            if (player != null && player.hasPermission(request.getPermission())) {
                return CompletableFuture.completedFuture(Boolean.TRUE);
            }
        }
        Plugin luckPerms = plugin.getProxy().getPluginManager().getPlugin("LuckPerms");
        return LuckPermsPermissionResolver.hasPermission(
                luckPerms == null ? null : luckPerms.getClass().getClassLoader(),
                request.getPlayerNames(), request.getPermission());
    }

    @Override
    public void close() {
        CompletableFuture<ConsoleResult> future = activeLocalFuture;
        ConsoleRequest request = activeLocalRequest;
        if (future != null && request != null) {
            future.complete(ConsoleResult.unavailable(
                    request, text("console.result.plugin-disabled"), "BungeeCord"));
        }
        activeLocalFuture = null;
        activeLocalRequest = null;
        localCommandRunning.set(false);
        socketExecutor.shutdownNow();
        endpointCircuits.clear();
    }

    private void clearLocalCapture(CompletableFuture<ConsoleResult> future) {
        if (activeLocalFuture == future) {
            activeLocalFuture = null;
            activeLocalRequest = null;
        }
    }

    private String text(String key) {
        Translations translations = plugin.getTranslations();
        return translations == null ? key : translations.get(key);
    }

    private String format(String key, String... replacements) {
        Translations translations = plugin.getTranslations();
        if (translations == null) {
            return key;
        }
        return translations.format(key, replacements);
    }
}
