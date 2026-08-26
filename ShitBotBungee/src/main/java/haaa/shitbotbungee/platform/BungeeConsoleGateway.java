package haaa.shitbotbungee.platform;

import haaa.shitbot.core.console.ConsoleRequest;
import haaa.shitbot.core.console.ConsoleResult;
import haaa.shitbot.core.console.ConsoleSettings;
import haaa.shitbot.core.console.ConsoleSocketProtocol;
import haaa.shitbot.core.console.LuckPermsPermissionResolver;
import haaa.shitbot.core.update.ReleaseAsset;
import haaa.shitbot.core.update.UpdateInfo;
import haaa.shitbot.core.update.UpdatePlatform;
import haaa.shitbot.core.util.FutureUtil;
import haaa.shitbot.core.util.NamedThreadFactory;
import net.md_5.bungee.api.CommandSender;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.plugin.Command;
import net.md_5.bungee.api.plugin.Plugin;

import java.lang.reflect.Array;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

public final class BungeeConsoleGateway implements AutoCloseable {
    private final Plugin plugin;
    private final AtomicBoolean localCommandRunning = new AtomicBoolean();
    private volatile CompletableFuture<ConsoleResult> activeLocalFuture;
    private volatile ConsoleRequest activeLocalRequest;
    private volatile Logger activeLogger;
    private volatile Handler activeHandler;
    private final ThreadPoolExecutor socketExecutor = new ThreadPoolExecutor(
            2, 2, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<Runnable>(32),
            new NamedThreadFactory("shitbot-bungee-console", true),
            new ThreadPoolExecutor.AbortPolicy());
    private final Map<String, EndpointCircuit> endpointCircuits =
            new ConcurrentHashMap<String, EndpointCircuit>();
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

    public CompletableFuture<java.util.List<ConsoleResult>> updateAllBackends(UpdateInfo release) {
        final ConsoleSettings.BackendTransport transport = backendTransport;
        if (transport == null || transport.getEndpoints().isEmpty()) {
            return CompletableFuture.completedFuture(
                    java.util.Collections.<ConsoleResult>emptyList());
        }
        if (release == null) {
            return FutureUtil.failedFuture(new IllegalArgumentException("Release metadata is missing"));
        }
        ReleaseAsset jarAsset = release.findJarAsset(UpdatePlatform.SPIGOT);
        if (jarAsset == null) {
            return FutureUtil.failedFuture(new java.io.IOException(
                    "Release does not contain exactly one ShitBotSpigot-*.jar asset"));
        }
        ReleaseAsset checksumAsset = release.findAsset(jarAsset.getName() + ".sha256");
        if (checksumAsset == null) {
            return FutureUtil.failedFuture(new java.io.IOException(
                    "Release is missing checksum asset " + jarAsset.getName() + ".sha256"));
        }

        final java.util.List<CompletableFuture<ConsoleResult>> requests =
                new java.util.ArrayList<CompletableFuture<ConsoleResult>>();
        for (ConsoleSettings.BackendEndpoint endpoint : transport.getEndpoints().values()) {
            ConsoleRequest request = ConsoleRequest.update(
                    endpoint.getName(), release, jarAsset, checksumAsset);
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
                request, "目标子服未配置已认证的 Console Socket，已拒绝不安全的 Plugin Message 回退。",
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
                                endpoint, transport, request, plugin.getDataFolder().toPath(), deadlineNanos);
                        circuit.recordSuccess();
                        result.complete(response);
                    } catch (Exception exception) {
                        circuit.recordFailure(System.nanoTime());
                        result.complete(ConsoleResult.unavailable(request,
                                "无法连接目标子服：" + safeMessage(exception), endpoint.getName()));
                    }
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
                                "绑定角色没有代理权限 " + request.getPermission(), "BungeeCord"));
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
                                "命令已提交执行，但结果捕获超时；请确认执行结果后再决定是否重试。",
                                "BungeeCord"));
                    } else {
                        result.complete(ConsoleResult.unavailable(
                                request, "代理控制台请求在命令提交前超时。", "BungeeCord"));
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
                    request, "已有代理控制台命令正在执行。", "BungeeCord"));
        }
        final CompletableFuture<ConsoleResult> future = new CompletableFuture<ConsoleResult>();
        final CommandOutputCapture capture = new CommandOutputCapture();
        final Logger logger;
        final Handler handler;
        final CommandSender sender;
        if (shouldCaptureConsoleLogs(request)) {
            logger = plugin.getProxy().getLogger();
            handler = new ConsoleLogHandler(capture);
            logger.addHandler(handler);
            activeLogger = logger;
            activeHandler = handler;
            sender = plugin.getProxy().getConsole();
        } else {
            logger = null;
            handler = null;
            sender = capturingSender(plugin.getProxy().getConsole(), capture);
        }
        activeLocalFuture = future;
        activeLocalRequest = request;
        final boolean accepted;
        try {
            commandDispatched.set(true);
            accepted = plugin.getProxy().getPluginManager().dispatchCommand(
                    sender, request.getCommand());
        } catch (Throwable throwable) {
            stopConsoleLogCapture(logger, handler);
            clearLocalCapture(future);
            localCommandRunning.set(false);
            future.complete(new ConsoleResult(request.getRequestId(), ConsoleResult.Status.FAILED,
                    throwable.getMessage(), "BungeeCord"));
            return future;
        }
        plugin.getProxy().getScheduler().schedule(plugin, new Runnable() {
            @Override
            public void run() {
                stopConsoleLogCapture(logger, handler);
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

    private boolean shouldCaptureConsoleLogs(ConsoleRequest request) {
        String expectedName = request.getConsoleLogPlugin();
        if (expectedName.isEmpty()) {
            return false;
        }
        String commandLine = request.getCommand();
        int separator = commandLine.indexOf(' ');
        String label = separator < 0 ? commandLine : commandLine.substring(0, separator);
        while (label.startsWith("/")) {
            label = label.substring(1);
        }
        Command registered = null;
        for (Map.Entry<String, Command> entry : plugin.getProxy().getPluginManager().getCommands()) {
            if (entry.getKey().equalsIgnoreCase(label)) {
                registered = entry.getValue();
                break;
            }
        }
        Plugin expectedPlugin = null;
        for (Plugin candidate : plugin.getProxy().getPluginManager().getPlugins()) {
            if (candidate.getDescription() != null
                    && expectedName.equalsIgnoreCase(candidate.getDescription().getName())) {
                expectedPlugin = candidate;
                break;
            }
        }
        return registered != null
                && expectedPlugin != null
                && registered.getClass().getClassLoader() == expectedPlugin.getClass().getClassLoader();
    }

    private void stopConsoleLogCapture(Logger logger, Handler handler) {
        if (logger == null || handler == null) {
            return;
        }
        logger.removeHandler(handler);
        if (activeHandler == handler) {
            activeLogger = null;
            activeHandler = null;
        }
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
        Logger logger = activeLogger;
        Handler handler = activeHandler;
        if (logger != null && handler != null) {
            logger.removeHandler(handler);
        }
        activeLogger = null;
        activeHandler = null;
        CompletableFuture<ConsoleResult> future = activeLocalFuture;
        ConsoleRequest request = activeLocalRequest;
        if (future != null && request != null) {
            future.complete(ConsoleResult.unavailable(request, "插件已关闭", "BungeeCord"));
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

    private static final class ConsoleLogHandler extends Handler {
        private final CommandOutputCapture capture;

        private ConsoleLogHandler(CommandOutputCapture capture) {
            this.capture = capture;
        }

        @Override
        public void publish(LogRecord record) {
            if (record == null || !isLoggable(record)) {
                return;
            }
            Level level = record.getLevel();
            capture.append("[" + (level == null ? "INFO" : level.getName()) + "] "
                    + (record.getMessage() == null ? "" : record.getMessage()));
            if (record.getThrown() != null) {
                capture.append("[SEVERE] " + record.getThrown().toString());
            }
        }

        @Override public void flush() { }
        @Override public void close() { }
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
