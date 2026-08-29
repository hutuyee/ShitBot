package haaa.shitbotnukkit;

import cn.nukkit.Player;
import cn.nukkit.command.Command;
import cn.nukkit.command.CommandSender;
import cn.nukkit.event.EventHandler;
import cn.nukkit.event.EventPriority;
import cn.nukkit.event.Listener;
import cn.nukkit.event.player.PlayerAsyncPreLoginEvent;
import cn.nukkit.event.player.PlayerChatEvent;
import cn.nukkit.event.player.PlayerJoinEvent;
import cn.nukkit.event.player.PlayerQuitEvent;
import cn.nukkit.plugin.PluginBase;
import haaa.shitbot.core.config.Settings;
import haaa.shitbot.core.console.ConsoleSettings;
import haaa.shitbot.core.database.EasyBotMigrationResult;
import haaa.shitbot.core.inventory.InventorySnapshot;
import haaa.shitbot.core.runtime.ShitBotRuntime;
import haaa.shitbot.core.service.EasyBotMigrationService;
import haaa.shitbot.core.service.LoginDecision;
import haaa.shitbot.core.update.UpdateChecker;
import haaa.shitbot.core.update.UpdateInfo;
import haaa.shitbot.core.update.UpdateInstallResult;
import haaa.shitbot.core.update.UpdatePlatform;
import haaa.shitbot.core.util.FutureUtil;
import haaa.shitbot.core.util.TextUtil;
import haaa.shitbotnukkit.config.NukkitConfigLoader;
import haaa.shitbotnukkit.platform.NukkitPlatformBridge;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public final class ShitBotNukkit extends PluginBase implements Listener {
    private final AtomicReference<ShitBotRuntime> runtimeReference =
            new AtomicReference<ShitBotRuntime>();
    private volatile boolean startupUnavailable = true;
    private volatile boolean stopping;
    private CompletableFuture<Boolean> reloadFuture;
    private NukkitConfigLoader configLoader;
    private NukkitPlatformBridge platformBridge;
    private UpdateChecker updateChecker;

    @Override
    public void onEnable() {
        stopping = false;
        configLoader = new NukkitConfigLoader(this);
        platformBridge = new NukkitPlatformBridge(this);
        updateChecker = new UpdateChecker(getDescription().getVersion(), platformBridge);
        getServer().getPluginManager().registerEvents(this, this);
        startUpdateCheck();

        try {
            Settings settings = configLoader.load();
            ConsoleSettings consoleSettings = configLoader.loadConsoleSettings();
            platformBridge.configureConsole(consoleSettings);
            final ShitBotRuntime runtime = new ShitBotRuntime(settings, consoleSettings, platformBridge);
            runtimeReference.set(runtime);
            runtime.startAsync().whenComplete(new java.util.function.BiConsumer<Void, Throwable>() {
                @Override
                public void accept(Void ignored, Throwable throwable) {
                    if (throwable != null) {
                        if (runtimeReference.compareAndSet(runtime, null)) {
                            startupUnavailable = true;
                        }
                        runtime.close();
                        platformBridge.error("ShitBot failed to start", FutureUtil.unwrap(throwable));
                        return;
                    }
                    if (stopping || runtimeReference.get() != runtime) {
                        runtime.close();
                        return;
                    }
                    startupUnavailable = false;
                    runtime.activate();
                    configureStartupNotification(runtime, true);
                    platformBridge.info("ShitBotNukkit enabled.");
                }
            });
        } catch (Throwable throwable) {
            platformBridge.error("Unable to load ShitBot config", throwable);
        }
    }

    public synchronized CompletableFuture<Boolean> reloadRuntime() {
        if (reloadFuture != null && !reloadFuture.isDone()) {
            return reloadFuture;
        }
        final CompletableFuture<Boolean> created = reloadRuntimeInternal();
        reloadFuture = created;
        created.whenComplete(new java.util.function.BiConsumer<Boolean, Throwable>() {
            @Override
            public void accept(Boolean ignored, Throwable throwable) {
                clearReloadFuture(created);
            }
        });
        return created;
    }

    private CompletableFuture<Boolean> reloadRuntimeInternal() {
        final ShitBotRuntime oldRuntime = runtimeReference.get();
        final ShitBotRuntime newRuntime;
        final ConsoleSettings consoleSettings;
        try {
            consoleSettings = configLoader.loadConsoleSettings();
            newRuntime = new ShitBotRuntime(configLoader.load(), consoleSettings, platformBridge);
        } catch (Throwable throwable) {
            platformBridge.error("Unable to reload config", throwable);
            return CompletableFuture.completedFuture(Boolean.FALSE);
        }
        return newRuntime.startAsync().handle(
                new java.util.function.BiFunction<Void, Throwable, Boolean>() {
                    @Override
                    public Boolean apply(Void ignored, Throwable throwable) {
                        if (throwable != null) {
                            newRuntime.close();
                            platformBridge.error("New runtime failed to initialize; old runtime kept",
                                    FutureUtil.unwrap(throwable));
                            return Boolean.FALSE;
                        }
                        if (stopping || !runtimeReference.compareAndSet(oldRuntime, newRuntime)) {
                            newRuntime.close();
                            return Boolean.FALSE;
                        }
                        platformBridge.configureConsole(consoleSettings);
                        if (oldRuntime != null) {
                            oldRuntime.close();
                        }
                        newRuntime.activate();
                        startupUnavailable = false;
                        return Boolean.TRUE;
                    }
                });
    }

    private synchronized void clearReloadFuture(CompletableFuture<Boolean> completed) {
        if (reloadFuture == completed) {
            reloadFuture = null;
        }
    }

    private void configureStartupNotification(ShitBotRuntime runtime, boolean initialStart) {
        Settings.ServerStartupNotice notice = runtime.getSettings().getOneBot().getServerStartupNotice();
        if (!notice.isEnabled() || !initialStart) {
            return;
        }
        if (!notice.getTargetServer().isEmpty()) {
            platformBridge.warn("onebot.notices.server-startup.target-server is only supported "
                    + "by proxy platforms; NukkitMOT will not send this startup notice.");
            return;
        }
        runtime.notifyServerStarted(platformBridge.getPlatformName());
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onAsyncPreLogin(PlayerAsyncPreLoginEvent event) {
        ShitBotRuntime runtime = runtimeReference.get();
        if (runtime == null) {
            event.disAllow(startupUnavailable
                    ? "§c绑定系统配置加载失败，请联系管理员。"
                    : "§cShitBot 正在重载，请稍后重试。");
            return;
        }
        if (!runtime.getSettings().getBinding().isEnabled()) {
            return;
        }
        try {
            LoginDecision decision = runtime.checkLogin(
                            event.getName(), event.getUuid() == null ? "" : event.getUuid().toString())
                    .get(runtime.getSettings().getBinding().getLoginDatabaseTimeoutSeconds(),
                            TimeUnit.SECONDS);
            if (!decision.isAllowed()) {
                event.disAllow(decision.getMessage());
            }
        } catch (Throwable throwable) {
            event.disAllow(TextUtil.color(
                    runtime.getSettings().getMessages().getKickDatabaseUnavailable()));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerChat(PlayerChatEvent event) {
        ShitBotRuntime runtime = runtimeReference.get();
        if (runtime != null) {
            runtime.forwardGameMessage(event.getPlayer().getName(), event.getMessage());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(final PlayerJoinEvent event) {
        final Player player = event.getPlayer();
        if (!player.hasPermission("shitbot.admin") || updateChecker == null) {
            return;
        }
        updateChecker.latestForNotificationAsync().thenAccept(
                new java.util.function.Consumer<UpdateInfo>() {
                    @Override
                    public void accept(final UpdateInfo info) {
                        if (!updateChecker.isUpdateAvailable(info)) {
                            return;
                        }
                        platformBridge.executeOnSenderThread(player, new Runnable() {
                            @Override
                            public void run() {
                                if (player.isOnline()) {
                                    sendUpdateNotice(player, info);
                                }
                            }
                        });
                    }
                });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        final ShitBotRuntime runtime = runtimeReference.get();
        if (runtime == null || !runtime.getSettings().getInventory().isEnabled()) {
            return;
        }
        final InventorySnapshot snapshot;
        try {
            snapshot = platformBridge.captureInventorySnapshot(event.getPlayer());
        } catch (Throwable throwable) {
            platformBridge.warn("Skipped quit inventory snapshot: " + errorMessage(throwable));
            return;
        }
        if (snapshot == null) {
            return;
        }
        runtime.getInventoryService().persistSnapshot(snapshot).exceptionally(
                new java.util.function.Function<Throwable, Void>() {
                    @Override
                    public Void apply(Throwable throwable) {
                        platformBridge.warn("Failed to save quit inventory snapshot: "
                                + errorMessage(throwable));
                        return null;
                    }
                });
    }

    @Override
    public boolean onCommand(final CommandSender sender,
                             Command command,
                             String label,
                             String[] args) {
        final ShitBotRuntime runtime = runtimeReference.get();
        if (runtime == null) {
            sender.sendMessage("§cShitBot 尚未初始化。");
            return true;
        }
        if (args.length == 0 || "status".equalsIgnoreCase(args[0])) {
            sender.sendMessage("§aShitBot §7" + runtime.describeStatus());
            return true;
        }
        if (!sender.hasPermission("shitbot.admin")) {
            sender.sendMessage(TextUtil.color(runtime.getSettings().getMessages().getNoPermission()));
            return true;
        }
        if ("reload".equalsIgnoreCase(args[0])) {
            sender.sendMessage(TextUtil.color(runtime.getSettings().getMessages().getReloadStarted()));
            reloadRuntime().whenComplete(new java.util.function.BiConsumer<Boolean, Throwable>() {
                @Override
                public void accept(final Boolean success, Throwable throwable) {
                    platformBridge.executeOnSenderThread(sender, new Runnable() {
                        @Override
                        public void run() {
                            ShitBotRuntime current = runtimeReference.get();
                            ShitBotRuntime messageRuntime = current == null ? runtime : current;
                            String message = Boolean.TRUE.equals(success)
                                    ? messageRuntime.getSettings().getMessages().getReloadSuccess()
                                    : messageRuntime.getSettings().getMessages().getReloadFailed();
                            sender.sendMessage(TextUtil.color(message));
                        }
                    });
                }
            });
            return true;
        }
        if ("update".equalsIgnoreCase(args[0])) {
            installUpdate(sender);
            return true;
        }
        if ("migrate".equalsIgnoreCase(args[0])) {
            migrateEasyBot(sender, runtime, args);
            return true;
        }
        if ("image".equalsIgnoreCase(args[0])) {
            renderImage(sender, runtime);
            return true;
        }
        sender.sendMessage("§e/shitbot status|reload|update|image|migrate easybot [EasyBot.db]");
        return true;
    }

    private void installUpdate(final CommandSender sender) {
        if (updateChecker == null) {
            sender.sendMessage("§c更新检查器尚未初始化。");
            return;
        }
        sender.sendMessage("§e正在后台检查并安装 GitHub Release...");
        updateChecker.updateAsync(UpdatePlatform.NUKKIT, getPluginJarPath()).whenComplete(
                new java.util.function.BiConsumer<UpdateInstallResult, Throwable>() {
                    @Override
                    public void accept(final UpdateInstallResult result, final Throwable throwable) {
                        platformBridge.executeOnSenderThread(sender, new Runnable() {
                            @Override
                            public void run() {
                                if (throwable != null) {
                                    sender.sendMessage("§c更新失败，现有 JAR 未替换: §f"
                                            + errorMessage(throwable));
                                    return;
                                }
                                sendInstallResult(sender, result);
                            }
                        });
                    }
                });
    }

    private void migrateEasyBot(final CommandSender sender,
                                final ShitBotRuntime runtime,
                                String[] args) {
        if (args.length < 2 || !"easybot".equalsIgnoreCase(args[1])) {
            sender.sendMessage("§e用法: /shitbot migrate easybot [EasyBot.db]");
            return;
        }
        final String fileName = args.length >= 3
                ? args[2] : EasyBotMigrationService.DEFAULT_FILE_NAME;
        sender.sendMessage("§e正在异步迁移 EasyBot 绑定数据: §f" + fileName);
        runtime.getEasyBotMigrationService().migrate(fileName).whenComplete(
                new java.util.function.BiConsumer<EasyBotMigrationResult, Throwable>() {
                    @Override
                    public void accept(final EasyBotMigrationResult result, final Throwable throwable) {
                        platformBridge.executeOnSenderThread(sender, new Runnable() {
                            @Override
                            public void run() {
                                sender.sendMessage(throwable == null
                                        ? "§aEasyBot 迁移完成: §f" + result.describe()
                                        : "§cEasyBot 迁移失败: §f" + errorMessage(throwable));
                            }
                        });
                    }
                });
    }

    private void renderImage(final CommandSender sender, final ShitBotRuntime runtime) {
        runtime.getImageService().renderOnlineImageAsync().whenComplete(
                new java.util.function.BiConsumer<byte[], Throwable>() {
                    @Override
                    public void accept(byte[] bytes, final Throwable throwable) {
                        platformBridge.executeOnSenderThread(sender, new Runnable() {
                            @Override
                            public void run() {
                                if (throwable != null) {
                                    sender.sendMessage("§c生成失败: " + errorMessage(throwable));
                                } else {
                                    Path path = runtime.getImageService().getOutputPath();
                                    sender.sendMessage("§a在线图片已生成: §f" + path.toAbsolutePath());
                                }
                            }
                        });
                    }
                });
    }

    public void sendUpdateNotice(CommandSender sender, UpdateInfo info) {
        if (sender == null || info == null || updateChecker == null) {
            return;
        }
        sender.sendMessage("§e[ShitBot] 发现新版本: §f" + updateChecker.getCurrentVersion()
                + " §7-> §a" + info.getLatestVersion());
        sender.sendMessage("§b" + info.getReleaseUrl());
    }

    private void sendInstallResult(CommandSender sender, UpdateInstallResult result) {
        if (result.getStatus() == UpdateInstallResult.Status.UP_TO_DATE) {
            sender.sendMessage("§a当前已是最新版本: §f" + result.getLatestVersion());
            return;
        }
        if (result.getStatus() == UpdateInstallResult.Status.ALREADY_INSTALLED) {
            sender.sendMessage("§e新版本 §f" + result.getLatestVersion()
                    + " §e已经替换到磁盘，请手动重启服务器生效。");
            return;
        }
        sender.sendMessage("§a更新包已校验并替换: §f" + result.getLatestVersion());
        sender.sendMessage("§7当前 JAR: §f" + result.getInstalledPath());
        sender.sendMessage("§7备份 JAR: §f" + result.getBackupPath());
        sender.sendMessage("§e请手动重启服务器生效；不要执行插件热重载。");
    }

    private void startUpdateCheck() {
        updateChecker.checkAsync().whenComplete(
                new java.util.function.BiConsumer<UpdateInfo, Throwable>() {
                    @Override
                    public void accept(UpdateInfo info, Throwable throwable) {
                        if (throwable != null) {
                            if (!stopping) {
                                platformBridge.warn("Unable to check for ShitBot updates: "
                                        + errorMessage(throwable));
                            }
                            return;
                        }
                        if (updateChecker.isUpdateAvailable(info)) {
                            platformBridge.info("ShitBot update available: "
                                    + updateChecker.getCurrentVersion() + " -> "
                                    + info.getLatestVersion() + " (" + info.getReleaseUrl() + ")");
                        }
                    }
                });
    }

    private String errorMessage(Throwable throwable) {
        Throwable cause = FutureUtil.unwrap(throwable);
        String message = cause.getMessage();
        return message == null || message.trim().isEmpty()
                ? cause.getClass().getSimpleName() : message;
    }

    public boolean isStartupUnavailable() {
        return startupUnavailable;
    }

    public ShitBotRuntime getRuntime() {
        return runtimeReference.get();
    }

    public NukkitPlatformBridge getPlatformBridge() {
        return platformBridge;
    }

    public UpdateChecker getUpdateChecker() {
        return updateChecker;
    }

    public Path getPluginJarPath() {
        return getFile().toPath();
    }

    @Override
    public void onDisable() {
        stopping = true;
        if (updateChecker != null) {
            updateChecker.close();
        }
        ShitBotRuntime runtime = runtimeReference.getAndSet(null);
        if (runtime != null) {
            runtime.close();
        }
        if (platformBridge != null) {
            platformBridge.close();
        }
    }
}
