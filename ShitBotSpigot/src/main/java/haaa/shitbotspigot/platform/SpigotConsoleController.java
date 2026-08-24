package haaa.shitbotspigot.platform;

import haaa.shitbot.core.console.ConsoleMessageCodec;
import haaa.shitbot.core.console.ConsoleRequest;
import haaa.shitbot.core.console.ConsoleResult;
import haaa.shitbot.core.console.ConsoleSettings;
import haaa.shitbot.core.util.NamedThreadFactory;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.PluginMessageListener;

import java.lang.reflect.Array;
import java.lang.reflect.Method;
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
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

public final class SpigotConsoleController implements PluginMessageListener, AutoCloseable {
    private static final int MAX_LOG_LINES = 100;
    private static final int MAX_LOG_CHARACTERS = 4000;

    private final JavaPlugin plugin;
    private final SchedulerAdapter scheduler;
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(
            new NamedThreadFactory("shitbot-console", true));
    private final Deque<PendingCommand> commandQueue = new ArrayDeque<PendingCommand>();
    private final TpsMonitor tpsMonitor;
    private final SpigotPermissionResolver permissionResolver;
    private final ConcurrentMap<String, Boolean> remoteRequestIds =
            new ConcurrentHashMap<String, Boolean>();
    private final ConcurrentMap<String, Boolean> cancelledRequestIds =
            new ConcurrentHashMap<String, Boolean>();
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

    public synchronized void configureBackendListener(ConsoleSettings.BackendListener settings) {
        if (socketServer != null) {
            socketServer.close();
            socketServer = null;
        }
        if (settings == null || !settings.isEnabled()) {
            return;
        }
        SpigotConsoleSocketServer newServer = new SpigotConsoleSocketServer(
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
        try {
            newServer.start();
            socketServer = newServer;
        } catch (Exception exception) {
            newServer.close();
            plugin.getLogger().severe("Unable to start ShitBot console listener: " + exception.getMessage());
        }
    }

    @Override
    public void onPluginMessageReceived(String channel, final Player player, byte[] data) {
        if (!ConsoleMessageCodec.CHANNEL.equals(channel) || player == null) {
            return;
        }
        final ConsoleRequest request;
        try {
            request = ConsoleMessageCodec.decodeRequest(data);
        } catch (Exception exception) {
            plugin.getLogger().warning("Ignored invalid ShitBot console request: " + exception.getMessage());
            return;
        }
        if (remoteRequestIds.putIfAbsent(request.getRequestId(), Boolean.TRUE) != null) {
            return;
        }
        timer.schedule(new Runnable() {
            @Override
            public void run() {
                remoteRequestIds.remove(request.getRequestId());
            }
        }, Math.max(60L, request.getTimeoutSeconds() * 2L), TimeUnit.SECONDS);
        execute(request).whenComplete(new java.util.function.BiConsumer<ConsoleResult, Throwable>() {
            @Override
            public void accept(ConsoleResult result, Throwable throwable) {
                ConsoleResult response = result;
                if (throwable != null || response == null) {
                    response = ConsoleResult.unavailable(request,
                            throwable == null ? "执行无返回" : throwable.getMessage(), serverName());
                }
                sendResponse(response, player);
            }
        });
    }

    private void sendResponse(final ConsoleResult result, final Player originalCarrier) {
        final byte[] payload;
        try {
            payload = ConsoleMessageCodec.encodeResult(result);
        } catch (Exception exception) {
            plugin.getLogger().warning("Unable to encode console response: " + exception.getMessage());
            return;
        }
        scheduler.executeGlobal(new Runnable() {
            @Override
            public void run() {
                final Player carrier = originalCarrier != null && originalCarrier.isOnline()
                        ? originalCarrier : firstOnlinePlayer();
                if (carrier == null) {
                    plugin.getLogger().warning("Console response dropped because no player can carry plugin messages.");
                    return;
                }
                scheduler.executeForPlayer(carrier, new Runnable() {
                    @Override
                    public void run() {
                        if (carrier.isOnline()) {
                            carrier.sendPluginMessage(plugin, ConsoleMessageCodec.CHANNEL, payload);
                        }
                    }
                });
            }
        });
    }

    private CompletableFuture<Boolean> hasPermission(ConsoleRequest request) {
        return permissionResolver.hasPermission(request.getPlayerNames(), request.getPermission());
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
                final CapturingHandler handler = new CapturingHandler();
                final Logger root = Logger.getLogger("");
                root.addHandler(handler);
                synchronized (commandQueue) {
                    activeLogger = root;
                    activeHandler = handler;
                }
                boolean dispatched;
                try {
                    dispatched = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), pending.request.getCommand());
                } catch (Throwable throwable) {
                    handler.append(Level.SEVERE, throwable.getMessage());
                    dispatched = false;
                }
                final boolean commandAccepted = dispatched;
                timer.schedule(new Runnable() {
                    @Override
                    public void run() {
                        root.removeHandler(handler);
                        synchronized (commandQueue) {
                            if (activeHandler == handler) {
                                activeHandler = null;
                                activeLogger = null;
                            }
                        }
                        String output = handler.output();
                        if (output.isEmpty()) {
                            output = commandAccepted ? "命令已执行，未捕获到日志。" : "命令不存在或执行器拒绝执行。";
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

    private Player firstOnlinePlayer() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player != null && player.isOnline()) {
                return player;
            }
        }
        return null;
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
        remoteRequestIds.clear();
        cancelledRequestIds.clear();
    }

    private static final class PendingCommand {
        private final ConsoleRequest request;
        private final CompletableFuture<ConsoleResult> future = new CompletableFuture<ConsoleResult>();

        private PendingCommand(ConsoleRequest request) {
            this.request = request;
        }
    }

    private static final class CapturingHandler extends Handler {
        private final StringBuilder output = new StringBuilder();
        private int lines;

        @Override
        public synchronized void publish(LogRecord record) {
            if (record != null && isLoggable(record)) {
                append(record.getLevel(), record.getMessage());
                if (record.getThrown() != null) {
                    append(Level.SEVERE, record.getThrown().toString());
                }
            }
        }

        private synchronized void append(Level level, String message) {
            if (lines >= MAX_LOG_LINES || output.length() >= MAX_LOG_CHARACTERS) {
                return;
            }
            if (output.length() > 0) {
                output.append('\n');
            }
            output.append('[').append(level == null ? "INFO" : level.getName()).append("] ")
                    .append(message == null ? "" : message);
            lines++;
        }

        private synchronized String output() {
            if (output.length() > MAX_LOG_CHARACTERS) {
                return output.substring(0, MAX_LOG_CHARACTERS);
            }
            return output.toString().trim();
        }

        @Override public void flush() { }
        @Override public void close() { }
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
