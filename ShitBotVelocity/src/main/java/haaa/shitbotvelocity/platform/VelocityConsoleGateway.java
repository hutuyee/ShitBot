package haaa.shitbotvelocity.platform;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.permission.Tristate;
import com.velocitypowered.api.plugin.PluginContainer;
import com.velocitypowered.api.proxy.ConsoleCommandSource;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import haaa.shitbot.core.console.ConsoleRequest;
import haaa.shitbot.core.console.ConsoleResult;
import haaa.shitbot.core.console.ConsoleSettings;
import haaa.shitbot.core.console.ConsoleSocketProtocol;
import haaa.shitbot.core.console.LuckPermsPermissionResolver;
import haaa.shitbot.core.util.NamedThreadFactory;
import net.kyori.adventure.audience.MessageType;
import net.kyori.adventure.identity.Identity;
import net.kyori.adventure.pointer.Pointers;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class VelocityConsoleGateway implements AutoCloseable {
    private final Object plugin;
    private final ProxyServer server;
    private final AtomicBoolean localCommandRunning = new AtomicBoolean();
    private final ExecutorService socketExecutor = Executors.newFixedThreadPool(2,
            new NamedThreadFactory("shitbot-velocity-console", true));
    private volatile ConsoleSettings.BackendTransport backendTransport;

    public VelocityConsoleGateway(Object plugin, ProxyServer server) {
        this.plugin = plugin;
        this.server = server;
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
        return CompletableFuture.supplyAsync(() -> {
            try {
                return ConsoleSocketProtocol.exchange(endpoint, transport, request);
            } catch (Exception exception) {
                return ConsoleResult.unavailable(request,
                        "无法连接目标子服：" + exception.getMessage(), endpoint.getName());
            }
        }, socketExecutor);
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
                execution = executeAuthorizedLocal(request);
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
                result.complete(ConsoleResult.unavailable(
                        request, "代理控制台请求执行超时。", "Velocity"));
            }
        }).delay(timeout, TimeUnit.SECONDS).schedule();
        return result;
    }

    private CompletableFuture<ConsoleResult> executeAuthorizedLocal(final ConsoleRequest request) {
        if (!localCommandRunning.compareAndSet(false, true)) {
            return CompletableFuture.completedFuture(ConsoleResult.unavailable(
                    request, "已有代理控制台命令正在执行。", "Velocity"));
        }
        final CompletableFuture<ConsoleResult> future = new CompletableFuture<>();
        final CapturingCommandSource source = new CapturingCommandSource(server.getConsoleCommandSource());
        server.getCommandManager().executeAsync(source, request.getCommand())
                .whenComplete((result, throwable) -> server.getScheduler().buildTask(plugin, () -> {
                    localCommandRunning.set(false);
                    if (throwable != null) {
                        future.complete(new ConsoleResult(request.getRequestId(), ConsoleResult.Status.FAILED,
                                throwable.getMessage(), "Velocity"));
                        return;
                    }
                    boolean success = Boolean.TRUE.equals(result);
                    String output = source.output();
                    if (output.isEmpty()) {
                        output = success ? "命令已执行，未捕获到输出。" : "命令不存在或执行器拒绝执行。";
                    }
                    future.complete(new ConsoleResult(request.getRequestId(),
                            success ? ConsoleResult.Status.SUCCESS : ConsoleResult.Status.FAILED,
                            output, "Velocity"));
                }).delay(Math.max(1, request.getCaptureSeconds()), TimeUnit.SECONDS).schedule());
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
        socketExecutor.shutdownNow();
    }

    private static final class CapturingCommandSource implements CommandSource {
        private final ConsoleCommandSource delegate;
        private final StringBuilder output = new StringBuilder();

        private CapturingCommandSource(ConsoleCommandSource delegate) {
            this.delegate = delegate;
        }

        @Override
        public void sendMessage(Component message) {
            append(message);
            delegate.sendMessage(message);
        }

        @Override
        public void sendMessage(Identity source, Component message, MessageType type) {
            append(message);
            delegate.sendMessage(source, message, type);
        }

        @Override
        public Tristate getPermissionValue(String permission) {
            return delegate.getPermissionValue(permission);
        }

        @Override
        public Pointers pointers() {
            return delegate.pointers();
        }

        private synchronized void append(Component message) {
            if (message == null || output.length() >= 4000) {
                return;
            }
            String line = PlainTextComponentSerializer.plainText().serialize(message);
            if (line.isEmpty()) {
                return;
            }
            if (output.length() > 0) {
                output.append('\n');
            }
            output.append(line);
        }

        private synchronized String output() {
            return output.length() > 4000 ? output.substring(0, 4000).trim() : output.toString().trim();
        }
    }
}
