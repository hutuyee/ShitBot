package haaa.shitbotvelocity.platform;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.permission.Tristate;
import com.velocitypowered.api.plugin.PluginContainer;
import com.velocitypowered.api.proxy.ConsoleCommandSource;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.ChannelIdentifier;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import haaa.shitbot.core.console.ConsoleMessageCodec;
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
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class VelocityConsoleGateway implements AutoCloseable {
    private final Object plugin;
    private final ProxyServer server;
    private final Logger logger;
    private final ChannelIdentifier channel;
    private final Map<String, PendingRequest> pending = new ConcurrentHashMap<>();
    private final AtomicBoolean localCommandRunning = new AtomicBoolean();
    private final ExecutorService socketExecutor = Executors.newFixedThreadPool(2,
            new NamedThreadFactory("shitbot-velocity-console", true));
    private volatile ConsoleSettings.BackendTransport backendTransport;

    public VelocityConsoleGateway(Object plugin,
                                  ProxyServer server,
                                  Logger logger,
                                  ChannelIdentifier channel) {
        this.plugin = plugin;
        this.server = server;
        this.logger = logger;
        this.channel = channel;
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

    @Subscribe
    public void onPluginMessage(PluginMessageEvent event) {
        if (!channel.equals(event.getIdentifier()) || !(event.getSource() instanceof ServerConnection)) {
            return;
        }
        event.setResult(PluginMessageEvent.ForwardResult.handled());
        try {
            ConsoleResult result = ConsoleMessageCodec.decodeResult(event.getData());
            PendingRequest request = pending.get(result.getRequestId());
            String sourceServer = ((ServerConnection) event.getSource()).getServerInfo().getName();
            if (request != null && request.serverName.equals(sourceServer)
                    && pending.remove(result.getRequestId(), request)) {
                request.future.complete(new ConsoleResult(result.getRequestId(), result.getStatus(),
                        result.getOutput(), sourceServer));
            }
        } catch (Exception exception) {
            logger.warn("Ignored invalid ShitBot console response: {}", exception.getMessage());
        }
    }

    private CompletableFuture<ConsoleResult> sendToBackend(final ConsoleRequest request) {
        final ConsoleSettings.BackendEndpoint endpoint = selectEndpoint(request);
        if (endpoint != null) {
            return sendToSocket(request, endpoint);
        }
        final RegisteredServer target = selectServer(request);
        if (target == null) {
            return CompletableFuture.completedFuture(ConsoleResult.unavailable(
                    request, "没有在线玩家可承载代理与子服通信。", "Velocity"));
        }
        final CompletableFuture<ConsoleResult> future = new CompletableFuture<>();
        final PendingRequest pendingRequest = new PendingRequest(target.getServerInfo().getName(), future);
        if (pending.putIfAbsent(request.getRequestId(), pendingRequest) != null) {
            return CompletableFuture.completedFuture(ConsoleResult.unavailable(
                    request, "重复的控制台请求已被拒绝。", "Velocity"));
        }
        try {
            boolean sent = target.sendPluginMessage(channel, ConsoleMessageCodec.encodeRequest(request));
            if (!sent) {
                pending.remove(request.getRequestId(), pendingRequest);
                return CompletableFuture.completedFuture(ConsoleResult.unavailable(
                        request, "子服通道不可用。", target.getServerInfo().getName()));
            }
        } catch (Exception exception) {
            pending.remove(request.getRequestId(), pendingRequest);
            return CompletableFuture.completedFuture(ConsoleResult.unavailable(
                    request, exception.getMessage(), target.getServerInfo().getName()));
        }
        long timeout = Math.max(request.getTimeoutSeconds(), request.getCaptureSeconds() + 5L);
        server.getScheduler().buildTask(plugin, () -> {
            if (pending.remove(request.getRequestId(), pendingRequest)) {
                future.complete(ConsoleResult.unavailable(
                        request, "等待子服响应超时。", target.getServerInfo().getName()));
            }
        }).delay(timeout, TimeUnit.SECONDS).schedule();
        return future;
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

    private RegisteredServer selectServer(ConsoleRequest request) {
        if (!request.getServer().isEmpty()) {
            Optional<RegisteredServer> configured = server.getServer(request.getServer());
            return configured.isPresent() && !configured.get().getPlayersConnected().isEmpty()
                    ? configured.get() : null;
        }
        for (String playerName : request.getPlayerNames()) {
            Optional<Player> player = server.getPlayer(playerName);
            if (player.isPresent() && player.get().getCurrentServer().isPresent()) {
                return player.get().getCurrentServer().get().getServer();
            }
        }
        ConsoleSettings.BackendTransport transport = backendTransport;
        if (transport != null && !transport.getDefaultServer().isEmpty()) {
            Optional<RegisteredServer> configured = server.getServer(transport.getDefaultServer());
            return configured.isPresent() && !configured.get().getPlayersConnected().isEmpty()
                    ? configured.get() : null;
        }
        if (transport != null && !transport.getEndpoints().isEmpty()) {
            return null;
        }
        List<RegisteredServer> servers = new ArrayList<>(server.getAllServers());
        servers.sort(Comparator.comparing(item -> item.getServerInfo().getName(), String.CASE_INSENSITIVE_ORDER));
        for (RegisteredServer registered : servers) {
            if (!registered.getPlayersConnected().isEmpty()) {
                return registered;
            }
        }
        return null;
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
        for (Map.Entry<String, PendingRequest> entry : pending.entrySet()) {
            if (pending.remove(entry.getKey(), entry.getValue())) {
                entry.getValue().future.completeExceptionally(new IllegalStateException("Plugin disabled"));
            }
        }
        socketExecutor.shutdownNow();
    }

    private static final class PendingRequest {
        private final String serverName;
        private final CompletableFuture<ConsoleResult> future;

        private PendingRequest(String serverName, CompletableFuture<ConsoleResult> future) {
            this.serverName = serverName;
            this.future = future;
        }
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
