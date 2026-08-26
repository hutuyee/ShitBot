package haaa.shitbotspigot.platform;

import haaa.shitbot.core.console.ConsoleRequest;
import haaa.shitbot.core.console.ConsoleResult;
import haaa.shitbot.core.console.ConsoleSettings;
import haaa.shitbot.core.update.BackendUpdatePayload;
import haaa.shitbot.core.update.UpdateInstallResult;
import haaa.shitbot.core.util.FutureUtil;
import haaa.shitbot.core.util.NamedThreadFactory;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.lang.reflect.Array;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

public final class SpigotConsoleController implements AutoCloseable {
    private static final int MAX_LOG_LINES = 100;
    private static final int MAX_LOG_CHARACTERS = 4000;
    private static final Pattern BEARER_SECRET = Pattern.compile("(?i)\\bBearer\\s+[^\\s,;]+");
    private static final Pattern NAMED_SECRET = Pattern.compile(
            "(?i)(\\\"?(?:authorization|access[-_ ]?token|token|password|passwd|pwd|secret)"
                    + "\\\"?\\s*[:=]\\s*)(?:\\\"[^\\\"]*\\\"|'[^']*'|[^\\s,;]+)");
    private static final Pattern DATABASE_URL = Pattern.compile(
            "(?i)\\b(?:jdbc:(?:mysql|mariadb):|mysql:)//[^\\s]+");

    private final JavaPlugin plugin;
    private final SchedulerAdapter scheduler;
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(
            new NamedThreadFactory("shitbot-console", true));
    private final Deque<PendingCommand> commandQueue = new ArrayDeque<PendingCommand>();
    private final TpsMonitor tpsMonitor;
    private final SpigotPermissionResolver permissionResolver;
    private final ConcurrentMap<String, Boolean> cancelledRequestIds =
            new ConcurrentHashMap<String, Boolean>();
    private volatile Function<BackendUpdatePayload, CompletableFuture<UpdateInstallResult>> updateInstaller;
    private boolean commandRunning;
    private Logger activeLogger;
    private Handler activeHandler;
    private SpigotConsoleSocketServer socketServer;

    public SpigotConsoleController(JavaPlugin plugin, SchedulerAdapter scheduler) {
        this.plugin = plugin;
        this.scheduler = scheduler;
        this.tpsMonitor = new TpsMonitor(plugin, scheduler);
        this.permissionResolver = new SpigotPermissionResolver(scheduler);
        this.tpsMonitor.start();
    }

    public CompletableFuture<ConsoleResult> execute(final ConsoleRequest request) {
        final CompletableFuture<ConsoleResult> result = new CompletableFuture<ConsoleResult>();
        CompletableFuture<ConsoleResult> operation = hasPermission(request).thenCompose(
                new java.util.function.Function<Boolean, CompletableFuture<ConsoleResult>>() {
                    @Override
                    public CompletableFuture<ConsoleResult> apply(Boolean allowed) {
                        if (isRequestCancelled(request.getRequestId())) {
                            return CompletableFuture.completedFuture(ConsoleResult.unavailable(
                                    request, "请求已超时取消。", serverName()));
                        }
                        if (!allowed.booleanValue()) {
                            return CompletableFuture.completedFuture(new ConsoleResult(
                                    request.getRequestId(), ConsoleResult.Status.NO_PERMISSION,
                                    "绑定角色没有权限 " + request.getPermission(), serverName()));
                        }
                        if (request.getOperation() == ConsoleRequest.Operation.UPDATE) {
                            return installBackendUpdate(request);
                        }
                        if (request.getOperation() == ConsoleRequest.Operation.TPS) {
                            return queryTps(request);
                        }
                        return enqueueCommand(request);
                    }
                });
        operation.whenComplete(new java.util.function.BiConsumer<ConsoleResult, Throwable>() {
            @Override
            public void accept(ConsoleResult value, Throwable throwable) {
                if (throwable == null) {
                    result.complete(value);
                } else {
                    result.completeExceptionally(throwable);
                }
            }
        });
        long timeout = Math.max(request.getTimeoutSeconds(), request.getCaptureSeconds() + 5L);
        try {
            timer.schedule(new Runnable() {
                @Override
                public void run() {
                    if (result.complete(ConsoleResult.unavailable(
                            request, "请求执行超时。", serverName()))) {
                        cancelRequest(request.getRequestId());
                    }
                }
            }, timeout, TimeUnit.SECONDS);
        } catch (RejectedExecutionException exception) {
            result.complete(ConsoleResult.unavailable(request, "插件已关闭", serverName()));
        }
        return result;
    }

    public synchronized void configureBackendListener(ConsoleSettings.BackendListener settings) throws IOException {
        if (settings == null || !settings.isEnabled()) {
            if (socketServer != null) {
                socketServer.close();
                socketServer = null;
            }
            return;
        }
        if (socketServer != null && socketServer.matches(settings)) {
            return;
        }

        SpigotConsoleSocketServer previous = socketServer;
        SpigotConsoleSocketServer newServer = createSocketServer(settings);
        if (previous == null || !previous.usesSamePort(settings)) {
            startSocketServer(newServer);
            socketServer = newServer;
            if (previous != null) {
                previous.close();
            }
            return;
        }

        ConsoleSettings.BackendListener previousSettings = previous.getSettings();
        previous.close();
        socketServer = null;
        try {
            startSocketServer(newServer);
            socketServer = newServer;
        } catch (IOException startFailure) {
            SpigotConsoleSocketServer restored = createSocketServer(previousSettings);
            try {
                startSocketServer(restored);
                socketServer = restored;
            } catch (IOException restoreFailure) {
                restored.close();
                startFailure.addSuppressed(restoreFailure);
            }
            throw startFailure;
        }
    }

    public void configureUpdateInstaller(
            Function<BackendUpdatePayload, CompletableFuture<UpdateInstallResult>> installer) {
        this.updateInstaller = installer;
    }

    private SpigotConsoleSocketServer createSocketServer(ConsoleSettings.BackendListener settings) {
        return new SpigotConsoleSocketServer(
                plugin, settings,
                new java.util.function.Function<ConsoleRequest, CompletableFuture<ConsoleResult>>() {
                    @Override
                    public CompletableFuture<ConsoleResult> apply(ConsoleRequest request) {
                        return execute(request);
                    }
                },
                new java.util.function.Consumer<String>() {
                    @Override
                    public void accept(String requestId) {
                        cancelRequest(requestId);
                    }
                });
    }

    private void startSocketServer(SpigotConsoleSocketServer server) throws IOException {
        try {
            server.start();
        } catch (IOException exception) {
            server.close();
            throw exception;
        } catch (RuntimeException exception) {
            server.close();
            throw new IOException("Unable to start console listener", exception);
        }
    }

    private CompletableFuture<Boolean> hasPermission(ConsoleRequest request) {
        return permissionResolver.hasPermission(request.getPlayerNames(), request.getPermission());
    }

    private CompletableFuture<ConsoleResult> installBackendUpdate(final ConsoleRequest request) {
        final BackendUpdatePayload payload = request.getUpdatePayload();
        final Function<BackendUpdatePayload, CompletableFuture<UpdateInstallResult>> installer = updateInstaller;
        if (payload == null || installer == null) {
            return CompletableFuture.completedFuture(ConsoleResult.unavailable(
                    request, "后端更新器尚未初始化。", serverName()));
        }
        final CompletableFuture<UpdateInstallResult> installation;
        try {
            installation = installer.apply(payload);
        } catch (Throwable throwable) {
            return CompletableFuture.completedFuture(new ConsoleResult(request.getRequestId(),
                    ConsoleResult.Status.FAILED, "更新启动失败：" + errorMessage(throwable), serverName()));
        }
        return installation.handle(
                new java.util.function.BiFunction<UpdateInstallResult, Throwable, ConsoleResult>() {
                    @Override
                    public ConsoleResult apply(UpdateInstallResult result, Throwable throwable) {
                        if (throwable != null) {
                            return new ConsoleResult(request.getRequestId(), ConsoleResult.Status.FAILED,
                                    "更新失败，现有 JAR 未替换：" + errorMessage(throwable), serverName());
                        }
                        return new ConsoleResult(request.getRequestId(), ConsoleResult.Status.SUCCESS,
                                describeUpdateResult(result), serverName());
                    }
                });
    }

    private String describeUpdateResult(UpdateInstallResult result) {
        if (result.getStatus() == UpdateInstallResult.Status.UP_TO_DATE) {
            return "已是最新版本 " + result.getLatestVersion() + "。";
        }
        if (result.getStatus() == UpdateInstallResult.Status.ALREADY_INSTALLED) {
            return "版本 " + result.getLatestVersion() + " 已替换到磁盘，等待手动重启。";
        }
        return "已校验并替换为 " + result.getLatestVersion()
                + "，备份：" + result.getBackupPath() + "；请手动重启。";
    }

    private String errorMessage(Throwable throwable) {
        Throwable cause = FutureUtil.unwrap(throwable);
        String message = cause.getMessage();
        return message == null || message.trim().isEmpty()
                ? cause.getClass().getSimpleName()
                : message;
    }

    private CompletableFuture<ConsoleResult> queryTps(final ConsoleRequest request) {
        final CompletableFuture<ConsoleResult> future = new CompletableFuture<ConsoleResult>();
        scheduler.executeGlobal(new Runnable() {
            @Override
            public void run() {
                try {
                    future.complete(new ConsoleResult(request.getRequestId(), ConsoleResult.Status.SUCCESS,
                            tpsMonitor.read(), serverName()));
                } catch (Throwable throwable) {
                    future.complete(new ConsoleResult(request.getRequestId(), ConsoleResult.Status.FAILED,
                            throwable.getMessage(), serverName()));
                }
            }
        });
        return future;
    }

    private CompletableFuture<ConsoleResult> enqueueCommand(ConsoleRequest request) {
        if (isRequestCancelled(request.getRequestId())) {
            return CompletableFuture.completedFuture(ConsoleResult.unavailable(
                    request, "请求已超时取消。", serverName()));
        }
        PendingCommand pending = new PendingCommand(request);
        synchronized (commandQueue) {
            if (isRequestCancelled(request.getRequestId())) {
                return CompletableFuture.completedFuture(ConsoleResult.unavailable(
                        request, "请求已超时取消。", serverName()));
            }
            if (commandQueue.size() >= 32) {
                return CompletableFuture.completedFuture(ConsoleResult.unavailable(
                        request, "控制台命令队列已满。", serverName()));
            }
            commandQueue.addLast(pending);
            if (!commandRunning) {
                commandRunning = true;
                startNextCommand();
            }
        }
        return pending.future;
    }

    private boolean isRequestCancelled(String requestId) {
        return requestId != null && cancelledRequestIds.containsKey(requestId);
    }

    private void cancelRequest(final String requestId) {
        if (requestId == null || requestId.isEmpty()) {
            return;
        }
        cancelledRequestIds.put(requestId, Boolean.TRUE);
        synchronized (commandQueue) {
            for (PendingCommand pending : commandQueue) {
                if (requestId.equals(pending.request.getRequestId())) {
                    pending.future.cancel(false);
                }
            }
        }
        if (!timer.isShutdown()) {
            timer.schedule(new Runnable() {
                @Override
                public void run() {
                    cancelledRequestIds.remove(requestId);
                }
            }, 2L, TimeUnit.MINUTES);
        }
    }

    private void startNextCommand() {
        final PendingCommand pending;
        synchronized (commandQueue) {
            pending = commandQueue.peekFirst();
            if (pending == null) {
                commandRunning = false;
                return;
            }
            if (pending.future.isCancelled()) {
                commandQueue.removeFirst();
                startNextCommand();
                return;
            }
        }
        scheduler.executeGlobal(new Runnable() {
            @Override
            public void run() {
                if (pending.future.isCancelled()) {
                    synchronized (commandQueue) {
                        commandQueue.removeFirstOccurrence(pending);
                    }
                    startNextCommand();
                    return;
                }
                final CommandOutputCapture capture = new CommandOutputCapture();
                final Logger logger;
                final Handler handler;
                final CommandSender sender;
                Plugin consoleLogPlugin = consoleLogPlugin(pending.request);
                if (consoleLogPlugin != null) {
                    logger = consoleLogPlugin.getLogger();
                    handler = new ConsoleLogHandler(capture, logger.getName());
                    logger.addHandler(handler);
                    synchronized (commandQueue) {
                        activeLogger = logger;
                        activeHandler = handler;
                    }
                    sender = Bukkit.getConsoleSender();
                } else {
                    logger = null;
                    handler = null;
                    sender = capturingSender(Bukkit.getConsoleSender(), capture);
                }
                boolean dispatched;
                try {
                    dispatched = Bukkit.dispatchCommand(sender, pending.request.getCommand());
                } catch (Throwable throwable) {
                    capture.append("命令执行异常：" + throwable.getMessage());
                    dispatched = false;
                }
                final boolean commandAccepted = dispatched;
                timer.schedule(new Runnable() {
                    @Override
                    public void run() {
                        stopConsoleLogCapture(logger, handler);
                        String output = capture.output();
                        if (output.isEmpty()) {
                            output = commandAccepted ? "命令已执行，未返回输出。" : "命令不存在或执行器拒绝执行。";
                        }
                        pending.future.complete(new ConsoleResult(
                                pending.request.getRequestId(),
                                commandAccepted ? ConsoleResult.Status.SUCCESS : ConsoleResult.Status.FAILED,
                                output,
                                serverName()));
                        synchronized (commandQueue) {
                            commandQueue.removeFirstOccurrence(pending);
                        }
                        startNextCommand();
                    }
                }, Math.max(1, pending.request.getCaptureSeconds()), TimeUnit.SECONDS);
            }
        });
    }

    private Plugin consoleLogPlugin(ConsoleRequest request) {
        String expectedPlugin = request.getConsoleLogPlugin();
        if (expectedPlugin.isEmpty()) {
            return null;
        }
        String command = request.getCommand();
        int separator = command.indexOf(' ');
        String label = separator < 0 ? command : command.substring(0, separator);
        while (label.startsWith("/")) {
            label = label.substring(1);
        }
        if (label.isEmpty()) {
            return null;
        }
        PluginCommand registered = Bukkit.getPluginCommand(label);
        return registered != null
                && registered.getPlugin() != null
                && expectedPlugin.equalsIgnoreCase(registered.getPlugin().getName())
                ? registered.getPlugin() : null;
    }

    private void stopConsoleLogCapture(Logger logger, Handler handler) {
        if (logger == null || handler == null) {
            return;
        }
        logger.removeHandler(handler);
        synchronized (commandQueue) {
            if (activeHandler == handler) {
                activeLogger = null;
                activeHandler = null;
            }
        }
    }

    private CommandSender capturingSender(final ConsoleCommandSender delegate, final CommandOutputCapture capture) {
        return (CommandSender) Proxy.newProxyInstance(
                ConsoleCommandSender.class.getClassLoader(),
                new Class<?>[]{ConsoleCommandSender.class},
                new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] arguments) throws Throwable {
                        if ("sendMessage".equals(method.getName()) || "sendRawMessage".equals(method.getName())) {
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

    private String serverName() {
        String name = Bukkit.getServer().getName();
        return name == null || name.trim().isEmpty() ? "Bukkit" : name;
    }

    @Override
    public void close() {
        timer.shutdownNow();
        synchronized (this) {
            if (socketServer != null) {
                socketServer.close();
                socketServer = null;
            }
        }
        synchronized (commandQueue) {
            if (activeLogger != null && activeHandler != null) {
                activeLogger.removeHandler(activeHandler);
                activeLogger = null;
                activeHandler = null;
            }
            for (PendingCommand pending : commandQueue) {
                pending.future.complete(ConsoleResult.unavailable(
                        pending.request, "插件已关闭", serverName()));
            }
            commandQueue.clear();
            commandRunning = false;
        }
        cancelledRequestIds.clear();
    }

    private static final class PendingCommand {
        private final ConsoleRequest request;
        private final CompletableFuture<ConsoleResult> future = new CompletableFuture<ConsoleResult>();

        private PendingCommand(ConsoleRequest request) {
            this.request = request;
        }
    }

    private static final class ConsoleLogHandler extends Handler {
        private final CommandOutputCapture capture;
        private final String loggerName;

        private ConsoleLogHandler(CommandOutputCapture capture, String loggerName) {
            this.capture = capture;
            this.loggerName = loggerName;
        }

        @Override
        public void publish(LogRecord record) {
            if (record == null || !isLoggable(record)) {
                return;
            }
            if (loggerName == null || !loggerName.equals(record.getLoggerName())) {
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
                if (argument instanceof String) {
                    append((String) argument);
                } else if (argument instanceof String[]) {
                    for (String value : (String[]) argument) {
                        append(value);
                    }
                }
            }
        }

        private synchronized void append(String message) {
            if (message == null || lines >= MAX_LOG_LINES || output.length() >= MAX_LOG_CHARACTERS) {
                return;
            }
            String redacted = redactSensitive(message);
            String[] messageLines = redacted.replace("\r\n", "\n").replace('\r', '\n').split("\\n", -1);
            for (String line : messageLines) {
                if (lines >= MAX_LOG_LINES || output.length() >= MAX_LOG_CHARACTERS) {
                    break;
                }
                if (output.length() > 0) {
                    output.append('\n');
                }
                int remaining = MAX_LOG_CHARACTERS - output.length();
                output.append(line, 0, Math.min(line.length(), remaining));
                lines++;
            }
        }

        private synchronized String output() {
            if (output.length() > MAX_LOG_CHARACTERS) {
                return output.substring(0, MAX_LOG_CHARACTERS);
            }
            return output.toString().trim();
        }
    }

    private static String redactSensitive(String message) {
        String redacted = BEARER_SECRET.matcher(message).replaceAll("Bearer <redacted>");
        redacted = NAMED_SECRET.matcher(redacted).replaceAll("$1<redacted>");
        return DATABASE_URL.matcher(redacted).replaceAll("<redacted database URL>");
    }

    private static final class TpsMonitor {
        private static final int MAX_SAMPLES = 900;
        private final JavaPlugin plugin;
        private final SchedulerAdapter scheduler;
        private final double[] samples = new double[MAX_SAMPLES];
        private int sampleCount;
        private int cursor;
        private long lastSampleNanos;

        private TpsMonitor(JavaPlugin plugin, SchedulerAdapter scheduler) {
            this.plugin = plugin;
            this.scheduler = scheduler;
        }

        private void start() {
            scheduler.runGlobalAtFixedRate(new Runnable() {
                @Override
                public void run() {
                    sampleTick();
                }
            }, 20L, 20L);
        }

        private synchronized void sampleTick() {
            long now = System.nanoTime();
            if (lastSampleNanos > 0L) {
                double elapsedSeconds = (now - lastSampleNanos) / 1_000_000_000.0D;
                double tps = elapsedSeconds <= 0.0D ? 20.0D : Math.min(20.0D, 20.0D / elapsedSeconds);
                samples[cursor] = tps;
                cursor = (cursor + 1) % samples.length;
                sampleCount = Math.min(samples.length, sampleCount + 1);
            }
            lastSampleNanos = now;
        }

        private String read() {
            Double essentials = essentialsTps();
            if (essentials != null) {
                return String.format(java.util.Locale.ROOT, "%.2f（EssentialsX）", essentials.doubleValue());
            }
            double[] nativeTps = nativeTps();
            if (nativeTps != null && nativeTps.length > 0) {
                return formatThree(nativeTps, "服务端原生");
            }
            synchronized (this) {
                if (sampleCount == 0) {
                    return "20.00（自采样，正在预热）";
                }
                return String.format(java.util.Locale.ROOT,
                        "%.2f / %.2f / %.2f（自采样 1/5/15 分钟）",
                        average(60), average(300), average(900));
            }
        }

        private Double essentialsTps() {
            try {
                Object essentials = Bukkit.getPluginManager().getPlugin("Essentials");
                if (essentials == null || !((org.bukkit.plugin.Plugin) essentials).isEnabled()) {
                    return null;
                }
                Object timerObject = essentials.getClass().getMethod("getTimer").invoke(essentials);
                Object value = timerObject.getClass().getMethod("getAverageTPS").invoke(timerObject);
                return value instanceof Number
                        ? Double.valueOf(Math.min(20.0D, ((Number) value).doubleValue())) : null;
            } catch (Throwable ignored) {
                return null;
            }
        }

        private double[] nativeTps() {
            try {
                Method method = Bukkit.getServer().getClass().getMethod("getTPS");
                Object value = method.invoke(Bukkit.getServer());
                if (value instanceof double[]) {
                    return (double[]) value;
                }
                if (value != null && value.getClass().isArray()) {
                    int length = Array.getLength(value);
                    double[] result = new double[length];
                    for (int index = 0; index < length; index++) {
                        result[index] = ((Number) Array.get(value, index)).doubleValue();
                    }
                    return result;
                }
            } catch (Throwable ignored) {
            }
            return null;
        }

        private String formatThree(double[] values, String source) {
            double one = Math.min(20.0D, values[0]);
            double five = Math.min(20.0D, values[Math.min(1, values.length - 1)]);
            double fifteen = Math.min(20.0D, values[Math.min(2, values.length - 1)]);
            return String.format(java.util.Locale.ROOT, "%.2f / %.2f / %.2f（%s 1/5/15 分钟）",
                    one, five, fifteen, source);
        }

        private double average(int requested) {
            int count = Math.min(requested, sampleCount);
            double total = 0.0D;
            for (int offset = 1; offset <= count; offset++) {
                int index = (cursor - offset + samples.length) % samples.length;
                total += samples[index];
            }
            return count == 0 ? 20.0D : total / count;
        }
    }
}
