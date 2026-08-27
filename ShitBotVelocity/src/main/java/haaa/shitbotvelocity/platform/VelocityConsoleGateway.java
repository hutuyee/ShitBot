package haaa.shitbotvelocity.platform;

import com.velocitypowered.api.plugin.PluginContainer;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import haaa.shitbot.core.console.ConsoleRequest;
import haaa.shitbot.core.console.ConsoleResult;
import haaa.shitbot.core.console.ConsoleSettings;
import haaa.shitbot.core.console.ConsoleSocketProtocol;
import haaa.shitbot.core.console.LatestLogCapture;
import haaa.shitbot.core.console.LuckPermsPermissionResolver;
import haaa.shitbot.core.util.NamedThreadFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ThreadPoolExecutor;

public final class VelocityConsoleGateway implements AutoCloseable {
    private final Object plugin;
    private final ProxyServer server;
    private final Path dataDirectory;
    private final AtomicBoolean localCommandRunning = new AtomicBoolean();
    private volatile CompletableFuture<ConsoleResult> activeLocalFuture;
    private volatile ConsoleRequest activeLocalRequest;
    private final ThreadPoolExecutor socketExecutor = new ThreadPoolExecutor(
            2, 2, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<Runnable>(32),
            new NamedThreadFactory("shitbot-velocity-console", true),
            new ThreadPoolExecutor.AbortPolicy());
    private final Map<String, EndpointCircuit> endpointCircuits =
            new ConcurrentHashMap<String, EndpointCircuit>();
    private volatile ConsoleSettings.BackendTransport backendTransport;

    public VelocityConsoleGateway(Object plugin, ProxyServer server, Path dataDirectory) {
        this.plugin = plugin;
        this.server = server;
        this.dataDirectory = dataDirectory;
    }

    public CompletableFuture<ConsoleResult> execute(ConsoleRequest request) {
        if (request.getTarget() == ConsoleSettings.Target.PROXY
                && request.getOperation() == ConsoleRequest.Operation.COMMAND) {
            return executeLocally(request);
        }
        return sendToBackend(request);
    }

    public void configure(ConsoleSettings settings) {
        backendTransport = settings == null ? null : settings.getBackendTransport();
    }

    private CompletableFuture<ConsoleResult> sendToBackend(final ConsoleRequest request) {
        final ConsoleSettings.BackendEndpoint endpoint = selectEndpoint(request);
        if (endpoint != null) {
            return sendToSocket(request, endpoint);
        }
        return CompletableFuture.completedFuture(ConsoleResult.unavailable(
                request, "目标子服未配置已认证的 Console Socket，已拒绝不安全的 Plugin Message 回退。",
                "Velocity"));
    }

    private CompletableFuture<ConsoleResult> sendToSocket(
            final ConsoleRequest request,
            final ConsoleSettings.BackendEndpoint endpoint) {
        final ConsoleSettings.BackendTransport transport = backendTransport;
        final CompletableFuture<ConsoleResult> result = new CompletableFuture<>();
        final long deadlineNanos = System.nanoTime()
                + TimeUnit.SECONDS.toNanos(request.getTimeoutSeconds());
        try {
            socketExecutor.execute(() -> {
                if (System.nanoTime() >= deadlineNanos) {
                    result.complete(ConsoleResult.unavailable(
                            request, "请求在等待后端连接前已过期。", endpoint.getName()));
                    return;
                }
                EndpointCircuit circuit = endpointCircuit(endpoint);
                if (circuit.isOpen(System.nanoTime())) {
                    result.complete(ConsoleResult.unavailable(
                            request, "目标子服连接连续失败，短暂熔断中。", endpoint.getName()));
                    return;
                }
                try {
                    ConsoleResult response = ConsoleSocketProtocol.exchange(
                            endpoint, transport, request, dataDirectory, deadlineNanos);
                    circuit.recordSuccess();
                    result.complete(response);
                } catch (Exception exception) {
                    circuit.recordFailure(System.nanoTime());
                    result.complete(ConsoleResult.unavailable(request,
                            "无法连接目标子服：" + safeMessage(exception), endpoint.getName()));
                }
            });
        } catch (RejectedExecutionException exception) {
            result.complete(ConsoleResult.unavailable(
                    request, "后端请求繁忙，等待队列已满。", endpoint.getName()));
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
            Optional<Player> player = server.getPlayer(playerName);
            if (player.isPresent() && player.get().getCurrentServer().isPresent()) {
                ConsoleSettings.BackendEndpoint endpoint = transport.getEndpoint(
                        player.get().getCurrentServer().get().getServerInfo().getName());
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
        final CompletableFuture<ConsoleResult> result = new CompletableFuture<>();
        hasPermission(request).whenComplete((allowed, throwable) -> {
            final CompletableFuture<ConsoleResult> execution;
            synchronized (gate) {
                if (result.isDone()) {
                    return;
                }
                if (throwable != null || !Boolean.TRUE.equals(allowed)) {
                    result.complete(new ConsoleResult(
                            request.getRequestId(), ConsoleResult.Status.NO_PERMISSION,
                            "绑定角色没有代理权限 " + request.getPermission(), "Velocity"));
                    return;
                }
                execution = executeAuthorizedLocal(request, commandDispatched);
            }
            execution.whenComplete((value, failure) -> {
                if (failure == null) {
                    result.complete(value);
                } else {
                    result.completeExceptionally(failure);
                }
            });
        });
        long timeout = Math.max(request.getTimeoutSeconds(), request.getCaptureSeconds() + 5L);
        server.getScheduler().buildTask(plugin, () -> {
            synchronized (gate) {
                if (commandDispatched.get()) {
                    result.complete(new ConsoleResult(request.getRequestId(),
                            ConsoleResult.Status.RESULT_TIMEOUT,
                            "命令已提交执行，但结果捕获超时；请确认执行结果后再决定是否重试。",
                            "Velocity"));
                } else {
                    result.complete(ConsoleResult.unavailable(
                            request, "代理控制台请求在命令提交前超时。", "Velocity"));
                }
            }
        }).delay(timeout, TimeUnit.SECONDS).schedule();
        return result;
    }

    private CompletableFuture<ConsoleResult> executeAuthorizedLocal(final ConsoleRequest request,
                                                                     AtomicBoolean commandDispatched) {
        if (!localCommandRunning.compareAndSet(false, true)) {
            return CompletableFuture.completedFuture(ConsoleResult.unavailable(
                    request, "已有代理控制台命令正在执行。", "Velocity"));
        }
        final CompletableFuture<ConsoleResult> future = new CompletableFuture<>();
        final LatestLogCapture capture = LatestLogCapture.begin();
        activeLocalFuture = future;
        activeLocalRequest = request;
        try {
            commandDispatched.set(true);
            server.getCommandManager().executeAsync(server.getConsoleCommandSource(), request.getCommand())
                    .whenComplete((result, throwable) -> server.getScheduler().buildTask(plugin, () -> {
                        clearLocalCapture(future);
                        localCommandRunning.set(false);
                        if (throwable != null) {
                            future.complete(new ConsoleResult(request.getRequestId(), ConsoleResult.Status.FAILED,
                                    throwable.getMessage(), "Velocity"));
                            return;
                        }
                        boolean success = Boolean.TRUE.equals(result);
                        String output;
                        try {
                            output = capture.readNewContent();
                        } catch (IOException exception) {
                            output = "无法读取 logs/latest.log：" + safeMessage(exception);
                        }
                        if (output.isEmpty()) {
                            output = success
                                    ? "命令已执行，latest.log 中没有新增日志。"
                                    : "命令不存在或执行器拒绝执行。";
                        }
                        future.complete(new ConsoleResult(request.getRequestId(),
                                success ? ConsoleResult.Status.SUCCESS : ConsoleResult.Status.FAILED,
                                output, "Velocity"));
                    }).delay(Math.max(1, request.getCaptureSeconds()), TimeUnit.SECONDS).schedule());
        } catch (Throwable throwable) {
            clearLocalCapture(future);
            localCommandRunning.set(false);
            future.complete(new ConsoleResult(request.getRequestId(), ConsoleResult.Status.FAILED,
                    throwable.getMessage(), "Velocity"));
        }
        return future;
    }

    private CompletableFuture<Boolean> hasPermission(ConsoleRequest request) {
        if (request.getPermission().isEmpty()) {
            return CompletableFuture.completedFuture(Boolean.TRUE);
        }
        for (String playerName : request.getPlayerNames()) {
            Optional<Player> player = server.getPlayer(playerName);
            if (player.isPresent() && player.get().hasPermission(request.getPermission())) {
                return CompletableFuture.completedFuture(Boolean.TRUE);
            }
        }
        Optional<PluginContainer> container = server.getPluginManager().getPlugin("luckperms");
        Object luckPerms = container.isPresent() && container.get().getInstance().isPresent()
                ? container.get().getInstance().get() : null;
        return LuckPermsPermissionResolver.hasPermission(
                luckPerms == null ? null : luckPerms.getClass().getClassLoader(),
                request.getPlayerNames(), request.getPermission());
    }

    @Override
    public void close() {
        CompletableFuture<ConsoleResult> future = activeLocalFuture;
        ConsoleRequest request = activeLocalRequest;
        if (future != null && request != null) {
            future.complete(ConsoleResult.unavailable(request, "插件已关闭", "Velocity"));
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
}
