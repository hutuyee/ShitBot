package haaa.shitbotbungee.platform;

import haaa.shitbot.core.console.ConsoleMessageCodec;
import haaa.shitbot.core.console.ConsoleRequest;
import haaa.shitbot.core.console.ConsoleResult;
import haaa.shitbot.core.console.ConsoleSettings;
import haaa.shitbot.core.console.ConsoleSocketProtocol;
import haaa.shitbot.core.console.LuckPermsPermissionResolver;
import haaa.shitbot.core.util.NamedThreadFactory;
import net.md_5.bungee.api.CommandSender;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.config.ServerInfo;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.connection.Server;
import net.md_5.bungee.api.event.PluginMessageEvent;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.api.plugin.Plugin;
import net.md_5.bungee.event.EventHandler;

import java.lang.reflect.Array;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public final class BungeeConsoleGateway implements Listener, AutoCloseable {
    private final Plugin plugin;
    private final Map<String, PendingRequest> pending =
            new ConcurrentHashMap<String, PendingRequest>();
    private final AtomicBoolean localCommandRunning = new AtomicBoolean();
    private volatile CompletableFuture<ConsoleResult> activeLocalFuture;
    private volatile ConsoleRequest activeLocalRequest;
    private final ExecutorService socketExecutor = Executors.newFixedThreadPool(2,
            new NamedThreadFactory("shitbot-bungee-console", true));
    private volatile ConsoleSettings.BackendTransport backendTransport;

    public BungeeConsoleGateway(Plugin plugin) {
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

    @EventHandler
    public void onPluginMessage(PluginMessageEvent event) {
        if (!ConsoleMessageCodec.CHANNEL.equals(event.getTag()) || !(event.getSender() instanceof Server)) {
            return;
        }
        event.setCancelled(true);
        try {
            ConsoleResult result = ConsoleMessageCodec.decodeResult(event.getData());
            PendingRequest request = pending.get(result.getRequestId());
            String sourceServer = ((Server) event.getSender()).getInfo().getName();
            if (request != null && request.serverName.equals(sourceServer)
                    && pending.remove(result.getRequestId(), request)) {
                request.future.complete(new ConsoleResult(result.getRequestId(), result.getStatus(),
                        result.getOutput(), sourceServer));
            }
        } catch (Exception exception) {
            plugin.getLogger().warning("Ignored invalid ShitBot console response: " + exception.getMessage());
        }
    }

    private CompletableFuture<ConsoleResult> sendToBackend(final ConsoleRequest request) {
        final ConsoleSettings.BackendEndpoint endpoint = selectEndpoint(request);
        if (endpoint != null) {
            return sendToSocket(request, endpoint);
        }
        final ServerInfo target = selectServer(request);
        if (target == null) {
            return CompletableFuture.completedFuture(ConsoleResult.unavailable(
                    request, "没有在线玩家可承载代理与子服通信。", "BungeeCord"));
        }
        final CompletableFuture<ConsoleResult> future = new CompletableFuture<ConsoleResult>();
        final PendingRequest pendingRequest = new PendingRequest(target.getName(), future);
        if (pending.putIfAbsent(request.getRequestId(), pendingRequest) != null) {
            return CompletableFuture.completedFuture(ConsoleResult.unavailable(
                    request, "重复的控制台请求已被拒绝。", "BungeeCord"));
        }
        try {
            boolean sent = target.sendData(
                    ConsoleMessageCodec.CHANNEL, ConsoleMessageCodec.encodeRequest(request), false);
            if (!sent) {
                pending.remove(request.getRequestId(), pendingRequest);
                return CompletableFuture.completedFuture(ConsoleResult.unavailable(
                        request, "子服通道不可用。", target.getName()));
            }
        } catch (Exception exception) {
            pending.remove(request.getRequestId(), pendingRequest);
            return CompletableFuture.completedFuture(ConsoleResult.unavailable(
                    request, exception.getMessage(), target.getName()));
        }
        long timeout = Math.max(request.getTimeoutSeconds(), request.getCaptureSeconds() + 5L);
        plugin.getProxy().getScheduler().schedule(plugin, new Runnable() {
            @Override
            public void run() {
                if (pending.remove(request.getRequestId(), pendingRequest)) {
                    future.complete(ConsoleResult.unavailable(
                            request, "等待子服响应超时。", target.getName()));
                }
            }
        }, timeout, TimeUnit.SECONDS);
        return future;
    }

    private CompletableFuture<ConsoleResult> sendToSocket(
            final ConsoleRequest request,
            final ConsoleSettings.BackendEndpoint endpoint) {
        final ConsoleSettings.BackendTransport transport = backendTransport;
        return CompletableFuture.supplyAsync(new java.util.function.Supplier<ConsoleResult>() {
            @Override
            public ConsoleResult get() {
                try {
                    return ConsoleSocketProtocol.exchange(endpoint, transport, request);
                } catch (Exception exception) {
                    return ConsoleResult.unavailable(request,
                            "无法连接目标子服：" + exception.getMessage(), endpoint.getName());
                }
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

    private ServerInfo selectServer(ConsoleRequest request) {
        ProxyServer proxy = plugin.getProxy();
        if (!request.getServer().isEmpty()) {
            ServerInfo configured = proxy.getServers().get(request.getServer());
            return configured != null && !configured.getPlayers().isEmpty() ? configured : null;
        }
        for (String playerName : request.getPlayerNames()) {
            ProxiedPlayer player = proxy.getPlayer(playerName);
            if (player != null && player.getServer() != null) {
                return player.getServer().getInfo();
            }
        }
        ConsoleSettings.BackendTransport transport = backendTransport;
        if (transport != null && !transport.getDefaultServer().isEmpty()) {
            ServerInfo configured = proxy.getServers().get(transport.getDefaultServer());
            return configured != null && !configured.getPlayers().isEmpty() ? configured : null;
        }
        if (transport != null && !transport.getEndpoints().isEmpty()) {
            return null;
        }
        List<ServerInfo> servers = new ArrayList<ServerInfo>(proxy.getServers().values());
        Collections.sort(servers, new Comparator<ServerInfo>() {
            @Override
            public int compare(ServerInfo first, ServerInfo second) {
                return first.getName().compareToIgnoreCase(second.getName());
            }
        });
        for (ServerInfo server : servers) {
            if (!server.getPlayers().isEmpty()) {
                return server;
            }
        }
        return null;
    }

    private CompletableFuture<ConsoleResult> executeLocally(final ConsoleRequest request) {
        final Object gate = new Object();
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
                                "绑定角色没有代理权限 " + request.getPermission(), "BungeeCord"));
                        return;
                    }
                    execution = executeAuthorizedLocal(request);
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
                    result.complete(ConsoleResult.unavailable(
                            request, "代理控制台请求执行超时。", "BungeeCord"));
                }
            }
        }, timeout, TimeUnit.SECONDS);
        return result;
    }

    private CompletableFuture<ConsoleResult> executeAuthorizedLocal(final ConsoleRequest request) {
        if (!localCommandRunning.compareAndSet(false, true)) {
            return CompletableFuture.completedFuture(ConsoleResult.unavailable(
                    request, "已有代理控制台命令正在执行。", "BungeeCord"));
        }
        final CompletableFuture<ConsoleResult> future = new CompletableFuture<ConsoleResult>();
        final CommandOutputCapture capture = new CommandOutputCapture();
        CommandSender sender = capturingSender(plugin.getProxy().getConsole(), capture);
        activeLocalFuture = future;
        activeLocalRequest = request;
        final boolean accepted;
        try {
            accepted = plugin.getProxy().getPluginManager().dispatchCommand(
                    sender, request.getCommand());
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
                String output = capture.output();
                if (output.isEmpty()) {
                    output = accepted ? "命令已执行，未返回输出。" : "命令不存在或执行器拒绝执行。";
                }
                future.complete(new ConsoleResult(request.getRequestId(),
                        accepted ? ConsoleResult.Status.SUCCESS : ConsoleResult.Status.FAILED,
                        output, "BungeeCord"));
                localCommandRunning.set(false);
            }
        }, Math.max(1, request.getCaptureSeconds()), TimeUnit.SECONDS);
        return future;
    }

    private CommandSender capturingSender(final CommandSender delegate, final CommandOutputCapture capture) {
        return (CommandSender) Proxy.newProxyInstance(
                CommandSender.class.getClassLoader(),
                new Class<?>[]{CommandSender.class},
                new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] arguments) throws Throwable {
                        if ("sendMessage".equals(method.getName())) {
                            capture.appendArguments(arguments);
                        }
                        try {
                            return method.invoke(delegate, arguments);
                        } catch (InvocationTargetException exception) {
                            throw exception.getCause();
                        }
                    }
                });
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
        for (Map.Entry<String, PendingRequest> entry : pending.entrySet()) {
            if (pending.remove(entry.getKey(), entry.getValue())) {
                entry.getValue().future.completeExceptionally(new IllegalStateException("Plugin disabled"));
            }
        }
        CompletableFuture<ConsoleResult> future = activeLocalFuture;
        ConsoleRequest request = activeLocalRequest;
        if (future != null && request != null) {
            future.complete(ConsoleResult.unavailable(request, "插件已关闭", "BungeeCord"));
        }
        activeLocalFuture = null;
        activeLocalRequest = null;
        localCommandRunning.set(false);
        socketExecutor.shutdownNow();
    }

    private void clearLocalCapture(CompletableFuture<ConsoleResult> future) {
        if (activeLocalFuture == future) {
            activeLocalFuture = null;
            activeLocalRequest = null;
        }
    }

    private static final class PendingRequest {
        private final String serverName;
        private final CompletableFuture<ConsoleResult> future;

        private PendingRequest(String serverName, CompletableFuture<ConsoleResult> future) {
            this.serverName = serverName;
            this.future = future;
        }
    }

    private static final class CommandOutputCapture {
        private final StringBuilder output = new StringBuilder();
        private int lines;

        private synchronized void appendArguments(Object[] arguments) {
            if (arguments == null) {
                return;
            }
            for (Object argument : arguments) {
                appendArgument(argument);
            }
        }

        private void appendArgument(Object argument) {
            if (argument == null) {
                return;
            }
            if (argument instanceof String) {
                append((String) argument);
                return;
            }
            if (argument instanceof BaseComponent) {
                append(((BaseComponent) argument).toLegacyText());
                return;
            }
            if (argument instanceof BaseComponent[]) {
                append(BaseComponent.toLegacyText((BaseComponent[]) argument));
                return;
            }
            Class<?> type = argument.getClass();
            if (type.isArray()) {
                int length = Array.getLength(argument);
                for (int index = 0; index < length; index++) {
                    appendArgument(Array.get(argument, index));
                }
            }
        }

        private synchronized void append(String message) {
            if (message == null || lines >= 100 || output.length() >= 4000) {
                return;
            }
            String[] messageLines = message.replace("\r\n", "\n").replace('\r', '\n').split("\\n", -1);
            for (String line : messageLines) {
                if (lines >= 100 || output.length() >= 4000) {
                    break;
                }
                if (output.length() > 0) {
                    output.append('\n');
                }
                int remaining = 4000 - output.length();
                output.append(line, 0, Math.min(line.length(), remaining));
                lines++;
            }
        }

        private synchronized String output() {
            return output.toString().trim();
        }
    }
}
