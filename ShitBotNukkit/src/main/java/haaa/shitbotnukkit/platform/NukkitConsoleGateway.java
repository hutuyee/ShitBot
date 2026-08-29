package haaa.shitbotnukkit.platform;

import cn.nukkit.Player;
import cn.nukkit.Server;
import haaa.shitbot.core.console.ConsoleRequest;
import haaa.shitbot.core.console.ConsoleResult;
import haaa.shitbot.core.console.ConsoleSettings;
import haaa.shitbot.core.console.LatestLogCapture;
import haaa.shitbot.core.console.LuckPermsPermissionResolver;
import haaa.shitbot.core.util.FutureUtil;
import haaa.shitbot.core.util.NamedThreadFactory;
import haaa.shitbotnukkit.ShitBotNukkit;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

final class NukkitConsoleGateway implements AutoCloseable {
    private final ShitBotNukkit plugin;
    private final Server server;
    private final NukkitPlatformBridge platform;
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(
            new NamedThreadFactory("shitbot-nukkit-console", true));
    private final AtomicBoolean commandRunning = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile boolean enabled;

    NukkitConsoleGateway(ShitBotNukkit plugin, NukkitPlatformBridge platform) {
        this.plugin = plugin;
        this.server = plugin.getServer();
        this.platform = platform;
    }

    void configure(ConsoleSettings settings) {
        enabled = settings != null && settings.isEnabled();
    }

    CompletableFuture<ConsoleResult> execute(final ConsoleRequest request) {
        if (request == null) {
            return CompletableFuture.completedFuture(new ConsoleResult(
                    "", ConsoleResult.Status.FAILED, "请求为空。", platform.serverName()));
        }
        if (closed.get() || !enabled) {
            return CompletableFuture.completedFuture(ConsoleResult.unavailable(
                    request, "QQ 控制台命令当前未启用。", platform.serverName()));
        }
        if (request.getTarget() != ConsoleSettings.Target.BACKEND) {
            return CompletableFuture.completedFuture(ConsoleResult.unavailable(
                    request, "Nukkit-MOT 不支持代理端执行目标。", platform.serverName()));
        }
        if (!request.getServer().isEmpty()) {
            return CompletableFuture.completedFuture(ConsoleResult.unavailable(
                    request, "Nukkit-MOT 为单服平台，不能指定目标子服。", platform.serverName()));
        }

        final CompletableFuture<ConsoleResult> response = new CompletableFuture<ConsoleResult>();
        long timeout = Math.max(request.getTimeoutSeconds(), request.getCaptureSeconds() + 5L);
        final ScheduledFuture<?> timeoutTask;
        try {
            timeoutTask = timer.schedule(new Runnable() {
                @Override
                public void run() {
                    response.complete(ConsoleResult.unavailable(
                            request, "请求执行超时。", platform.serverName()));
                }
            }, timeout, TimeUnit.SECONDS);
        } catch (RejectedExecutionException exception) {
            response.complete(ConsoleResult.unavailable(
                    request, "插件已关闭。", platform.serverName()));
            return response;
        }
        response.whenComplete(new java.util.function.BiConsumer<ConsoleResult, Throwable>() {
            @Override
            public void accept(ConsoleResult ignored, Throwable throwable) {
                timeoutTask.cancel(false);
            }
        });

        hasPermission(request).whenComplete(new java.util.function.BiConsumer<Boolean, Throwable>() {
            @Override
            public void accept(Boolean allowed, Throwable throwable) {
                if (response.isDone()) {
                    return;
                }
                if (throwable != null) {
                    response.complete(new ConsoleResult(
                            request.getRequestId(), ConsoleResult.Status.FAILED,
                            "权限检查失败：" + errorMessage(throwable), platform.serverName()));
                    return;
                }
                if (!Boolean.TRUE.equals(allowed)) {
                    response.complete(new ConsoleResult(
                            request.getRequestId(), ConsoleResult.Status.NO_PERMISSION,
                            "绑定角色没有权限 " + request.getPermission(), platform.serverName()));
                    return;
                }
                CompletableFuture<ConsoleResult> operation;
                if (request.getOperation() == ConsoleRequest.Operation.TPS) {
                    operation = queryTps(request);
                } else if (request.getOperation() == ConsoleRequest.Operation.COMMAND) {
                    operation = executeCommand(request);
                } else {
                    operation = CompletableFuture.completedFuture(ConsoleResult.unavailable(
                            request, "Nukkit-MOT 不接受代理下发的更新请求。", platform.serverName()));
                }
                operation.whenComplete(new java.util.function.BiConsumer<ConsoleResult, Throwable>() {
                    @Override
                    public void accept(ConsoleResult result, Throwable operationFailure) {
                        if (operationFailure == null) {
                            response.complete(result);
                        } else {
                            response.complete(new ConsoleResult(
                                    request.getRequestId(), ConsoleResult.Status.FAILED,
                                    errorMessage(operationFailure), platform.serverName()));
                        }
                    }
                });
            }
        });
        return response;
    }

    private CompletableFuture<Boolean> hasPermission(final ConsoleRequest request) {
        if (request.getPermission().isEmpty()) {
            return CompletableFuture.completedFuture(Boolean.TRUE);
        }
        if (request.getPlayerNames().isEmpty()) {
            return CompletableFuture.completedFuture(Boolean.FALSE);
        }
        final CompletableFuture<Boolean> onlineCheck = new CompletableFuture<Boolean>();
        try {
            platform.executeOnPlatformThread(new Runnable() {
                @Override
                public void run() {
                    try {
                        for (String playerName : request.getPlayerNames()) {
                            Player player = server.getPlayerExact(playerName);
                            if (player != null && player.isOnline()
                                    && player.hasPermission(request.getPermission())) {
                                onlineCheck.complete(Boolean.TRUE);
                                return;
                            }
                        }
                        onlineCheck.complete(Boolean.FALSE);
                    } catch (Throwable throwable) {
                        onlineCheck.completeExceptionally(throwable);
                    }
                }
            });
        } catch (Throwable throwable) {
            onlineCheck.completeExceptionally(throwable);
        }
        return onlineCheck.thenCompose(
                new java.util.function.Function<Boolean, CompletableFuture<Boolean>>() {
                    @Override
                    public CompletableFuture<Boolean> apply(Boolean allowed) {
                        if (Boolean.TRUE.equals(allowed)) {
                            return CompletableFuture.completedFuture(Boolean.TRUE);
                        }
                        return LuckPermsPermissionResolver.hasPermission(
                                plugin.getClass().getClassLoader(),
                                request.getPlayerNames(),
                                request.getPermission());
                    }
                });
    }

    private CompletableFuture<ConsoleResult> queryTps(final ConsoleRequest request) {
        final CompletableFuture<ConsoleResult> result = new CompletableFuture<ConsoleResult>();
        try {
            platform.executeOnPlatformThread(new Runnable() {
                @Override
                public void run() {
                    try {
                        String output = "当前 " + server.getTicksPerSecond()
                                + "，平均 " + server.getTicksPerSecondAverage()
                                + "，负载 " + server.getTickUsage() + "%";
                        result.complete(new ConsoleResult(
                                request.getRequestId(), ConsoleResult.Status.SUCCESS,
                                output, platform.serverName()));
                    } catch (Throwable throwable) {
                        result.completeExceptionally(throwable);
                    }
                }
            });
        } catch (Throwable throwable) {
            result.completeExceptionally(throwable);
        }
        return result;
    }

    private CompletableFuture<ConsoleResult> executeCommand(final ConsoleRequest request) {
        if (!commandRunning.compareAndSet(false, true)) {
            return CompletableFuture.completedFuture(ConsoleResult.unavailable(
                    request, "已有一条控制台命令正在捕获输出，请稍后重试。", platform.serverName()));
        }
        final CompletableFuture<ConsoleResult> result = new CompletableFuture<ConsoleResult>();
        try {
            platform.executeOnPlatformThread(new Runnable() {
                @Override
                public void run() {
                    final LatestLogCapture capture;
                    try {
                        capture = LatestLogCapture.begin(
                                Paths.get(server.getDataPath(), "logs", "server.log"),
                                privacyPlayerNames(request));
                    } catch (Throwable throwable) {
                        commandRunning.set(false);
                        result.completeExceptionally(throwable);
                        return;
                    }
                    boolean dispatched;
                    String dispatchError = "";
                    try {
                        dispatched = server.dispatchCommand(
                                server.getConsoleSender(), request.getCommand());
                    } catch (Throwable throwable) {
                        dispatched = false;
                        dispatchError = "命令执行异常：" + errorMessage(throwable);
                    }
                    final boolean commandAccepted = dispatched;
                    final String commandError = dispatchError;
                    try {
                        timer.schedule(new Runnable() {
                            @Override
                            public void run() {
                                completeCommand(request, capture, commandAccepted, commandError, result);
                            }
                        }, Math.max(1, request.getCaptureSeconds()), TimeUnit.SECONDS);
                    } catch (RejectedExecutionException exception) {
                        commandRunning.set(false);
                        result.complete(ConsoleResult.unavailable(
                                request, "插件已关闭。", platform.serverName()));
                    }
                }
            });
        } catch (Throwable throwable) {
            commandRunning.set(false);
            result.completeExceptionally(throwable);
        }
        return result;
    }

    private void completeCommand(ConsoleRequest request,
                                 LatestLogCapture capture,
                                 boolean commandAccepted,
                                 String commandError,
                                 CompletableFuture<ConsoleResult> result) {
        String output;
        try {
            output = capture.readNewContent();
        } catch (Throwable throwable) {
            output = "无法读取 logs/server.log：" + errorMessage(throwable);
        }
        if (output.isEmpty()) {
            output = commandError.isEmpty()
                    ? (commandAccepted
                    ? "命令已执行，server.log 中没有新增日志。"
                    : "命令不存在或执行器拒绝执行。")
                    : commandError;
        }
        try {
            result.complete(new ConsoleResult(
                    request.getRequestId(),
                    commandAccepted ? ConsoleResult.Status.SUCCESS : ConsoleResult.Status.FAILED,
                    output,
                    platform.serverName()));
        } finally {
            commandRunning.set(false);
        }
    }

    private List<String> privacyPlayerNames(ConsoleRequest request) {
        List<String> names = new ArrayList<String>();
        if (request != null && request.getPlayerNames() != null) {
            names.addAll(request.getPlayerNames());
        }
        for (Player player : server.getOnlinePlayers().values()) {
            if (player != null && player.getName() != null) {
                names.add(player.getName());
            }
        }
        return names;
    }

    private String errorMessage(Throwable throwable) {
        Throwable cause = FutureUtil.unwrap(throwable);
        String message = cause.getMessage();
        return message == null || message.trim().isEmpty()
                ? cause.getClass().getSimpleName() : message;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        enabled = false;
        timer.shutdownNow();
    }
}
