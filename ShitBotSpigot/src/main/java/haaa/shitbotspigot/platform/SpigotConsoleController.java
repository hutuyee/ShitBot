package haaa.shitbotspigot.platform;

import haaa.shitbot.core.config.Translations;
import haaa.shitbot.core.console.ConsoleRequest;
import haaa.shitbot.core.console.ConsoleResult;
import haaa.shitbot.core.console.ConsoleSettings;
import haaa.shitbot.core.console.LatestLogCapture;
import haaa.shitbot.core.update.BackendUpdatePayload;
import haaa.shitbot.core.update.UpdateInstallResult;
import haaa.shitbot.core.util.FutureUtil;
import haaa.shitbot.core.util.NamedThreadFactory;
import haaa.shitbotspigot.ShitBotSpigot;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
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
import java.util.function.Function;

public final class SpigotConsoleController implements AutoCloseable {
    private final ShitBotSpigot plugin;
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
    private SpigotConsoleSocketServer socketServer;

    public SpigotConsoleController(ShitBotSpigot plugin, SchedulerAdapter scheduler) {
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
                                    request, text("console.result.request-cancelled"), serverName()));
                        }
                        if (!allowed.booleanValue()) {
                            return CompletableFuture.completedFuture(new ConsoleResult(
                                    request.getRequestId(), ConsoleResult.Status.NO_PERMISSION,
                                    format("console.result.permission-denied",
                                            "%permission%", request.getPermission()), serverName()));
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
                            request, text("console.result.execution-timeout"), serverName()))) {
                        cancelRequest(request.getRequestId());
                    }
                }
            }, timeout, TimeUnit.SECONDS);
        } catch (RejectedExecutionException exception) {
            result.complete(ConsoleResult.unavailable(
                    request, text("console.result.plugin-disabled"), serverName()));
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
                    request, text("console.result.backend-updater-unavailable"), serverName()));
        }
        final CompletableFuture<UpdateInstallResult> installation;
        try {
            installation = installer.apply(payload);
        } catch (Throwable throwable) {
            return CompletableFuture.completedFuture(new ConsoleResult(request.getRequestId(),
                    ConsoleResult.Status.FAILED, format("console.result.update-start-failed",
                            "%error%", errorMessage(throwable)), serverName()));
        }
        return installation.handle(
                new java.util.function.BiFunction<UpdateInstallResult, Throwable, ConsoleResult>() {
                    @Override
                    public ConsoleResult apply(UpdateInstallResult result, Throwable throwable) {
                        if (throwable != null) {
                            return new ConsoleResult(request.getRequestId(), ConsoleResult.Status.FAILED,
                                    format("console.result.update-failed",
                                            "%error%", errorMessage(throwable)), serverName());
                        }
                        return new ConsoleResult(request.getRequestId(), ConsoleResult.Status.SUCCESS,
                                describeUpdateResult(result), serverName());
                    }
                });
    }

    private String describeUpdateResult(UpdateInstallResult result) {
        if (result.getStatus() == UpdateInstallResult.Status.UP_TO_DATE) {
            return format("console.result.update-up-to-date",
                    "%version%", result.getLatestVersion());
        }
        if (result.getStatus() == UpdateInstallResult.Status.ALREADY_INSTALLED) {
            return format("console.result.update-already-installed",
                    "%version%", result.getLatestVersion());
        }
        return format("console.result.update-installed",
                "%version%", result.getLatestVersion(),
                "%backup%", String.valueOf(result.getBackupPath()));
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
                    request, text("console.result.request-cancelled"), serverName()));
        }
        PendingCommand pending = new PendingCommand(request);
        synchronized (commandQueue) {
            if (isRequestCancelled(request.getRequestId())) {
                return CompletableFuture.completedFuture(ConsoleResult.unavailable(
                        request, text("console.result.request-cancelled"), serverName()));
            }
            if (commandQueue.size() >= 32) {
                return CompletableFuture.completedFuture(ConsoleResult.unavailable(
                        request, text("console.result.console-queue-full"), serverName()));
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
                final LatestLogCapture capture = LatestLogCapture.begin(
                        privacyPlayerNames(pending.request));
                boolean dispatched;
                String dispatchError = "";
                try {
                    dispatched = Bukkit.dispatchCommand(
                            Bukkit.getConsoleSender(), pending.request.getCommand());
                } catch (Throwable throwable) {
                    dispatchError = format("console.result.command-error",
                            "%error%", errorMessage(throwable));
                    dispatched = false;
                }
                final boolean commandAccepted = dispatched;
                final String commandError = dispatchError;
                timer.schedule(new Runnable() {
                    @Override
                    public void run() {
                        String output;
                        try {
                            output = capture.readNewContent();
                        } catch (IOException exception) {
                            output = format("console.result.log-read-failed",
                                    "%error%", errorMessage(exception));
                        }
                        if (output.isEmpty()) {
                            output = commandError.isEmpty()
                                    ? (commandAccepted
                                    ? text("console.result.no-new-log")
                                    : text("console.result.command-rejected"))
                                    : commandError;
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

    private List<String> privacyPlayerNames(ConsoleRequest request) {
        List<String> playerNames = new ArrayList<String>(request.getPlayerNames());
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player != null && player.getName() != null) {
                playerNames.add(player.getName());
            }
        }
        return playerNames;
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
            for (PendingCommand pending : commandQueue) {
                pending.future.complete(ConsoleResult.unavailable(
                        pending.request, text("console.result.plugin-disabled"), serverName()));
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

    private String text(String key) {
        Translations translations = plugin.getTranslations();
        return translations == null ? key : translations.get(key);
    }

    private String format(String key, String... replacements) {
        Translations translations = plugin.getTranslations();
        return translations == null ? key : translations.format(key, replacements);
    }

    private static final class TpsMonitor {
        private static final int MAX_SAMPLES = 900;
        private final ShitBotSpigot plugin;
        private final SchedulerAdapter scheduler;
        private final double[] samples = new double[MAX_SAMPLES];
        private int sampleCount;
        private int cursor;
        private long lastSampleNanos;

        private TpsMonitor(ShitBotSpigot plugin, SchedulerAdapter scheduler) {
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
                return languageFormat("console.tps.single",
                        "%value%", String.format(java.util.Locale.ROOT, "%.2f", essentials.doubleValue()),
                        "%source%", "EssentialsX");
            }
            double[] nativeTps = nativeTps();
            if (nativeTps != null && nativeTps.length > 0) {
                return formatThree(nativeTps, languageText("console.tps.native-source"));
            }
            synchronized (this) {
                if (sampleCount == 0) {
                    return languageText("console.tps.warming-up");
                }
                return languageFormat("console.tps.three",
                        "%one%", String.format(java.util.Locale.ROOT, "%.2f", average(60)),
                        "%five%", String.format(java.util.Locale.ROOT, "%.2f", average(300)),
                        "%fifteen%", String.format(java.util.Locale.ROOT, "%.2f", average(900)),
                        "%source%", languageText("console.tps.sampled-source"));
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
            return languageFormat("console.tps.three",
                    "%one%", String.format(java.util.Locale.ROOT, "%.2f", one),
                    "%five%", String.format(java.util.Locale.ROOT, "%.2f", five),
                    "%fifteen%", String.format(java.util.Locale.ROOT, "%.2f", fifteen),
                    "%source%", source);
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

        private String languageText(String key) {
            Translations translations = plugin.getTranslations();
            return translations == null ? key : translations.get(key);
        }

        private String languageFormat(String key, String... replacements) {
            Translations translations = plugin.getTranslations();
            return translations == null ? key : translations.format(key, replacements);
        }
    }
}
