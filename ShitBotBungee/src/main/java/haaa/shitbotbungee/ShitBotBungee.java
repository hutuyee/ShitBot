package haaa.shitbotbungee;

import haaa.shitbot.core.config.Settings;
import haaa.shitbot.core.console.ConsoleSettings;
import haaa.shitbot.core.runtime.ShitBotRuntime;
import haaa.shitbot.core.platform.PlatformBridge;
import haaa.shitbot.core.update.UpdateChecker;
import haaa.shitbot.core.update.UpdateInfo;
import haaa.shitbot.core.util.FutureUtil;
import haaa.shitbotbungee.command.ShitBotCommand;
import haaa.shitbotbungee.config.BungeeConfigLoader;
import haaa.shitbotbungee.listener.PlayerChatListener;
import haaa.shitbotbungee.listener.PlayerLoginListener;
import haaa.shitbotbungee.platform.BungeePlatformBridge;
import net.md_5.bungee.api.CommandSender;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.plugin.Plugin;
import org.bstats.bungeecord.Metrics;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

public final class ShitBotBungee extends Plugin {
    private static final int BSTATS_PLUGIN_ID = 33866;
    private final AtomicReference<ShitBotRuntime> runtimeReference = new AtomicReference<ShitBotRuntime>();
    private volatile boolean startupUnavailable = true;
    private volatile boolean stopping;
    private CompletableFuture<Boolean> reloadFuture;
    private BungeeConfigLoader configLoader;
    private BungeePlatformBridge platformBridge;
    private UpdateChecker updateChecker;
    private PlatformBridge.ServerAvailabilityWatch startupWatch;
    private boolean startupNoticeTriggered;

    @Override
    public void onEnable() {
        stopping = false;
        new Metrics(this, BSTATS_PLUGIN_ID);
        startupNoticeTriggered = false;
        this.configLoader = new BungeeConfigLoader(this);
        this.platformBridge = new BungeePlatformBridge(this);
        this.updateChecker = new UpdateChecker(getDescription().getVersion(), platformBridge);
        startUpdateCheck();
        ProxyServer.getInstance().getPluginManager().registerListener(this, new PlayerLoginListener(this));
        ProxyServer.getInstance().getPluginManager().registerListener(this, new PlayerChatListener(this));
        ProxyServer.getInstance().getPluginManager().registerCommand(this, new ShitBotCommand(this));

        try {
            Settings settings = configLoader.load();
            ConsoleSettings consoleSettings = configLoader.loadConsoleSettings();
            platformBridge.configureConsole(consoleSettings);
            ShitBotRuntime runtime = new ShitBotRuntime(settings, consoleSettings, platformBridge);
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
                    } else {
                        if (stopping || runtimeReference.get() != runtime) {
                            runtime.close();
                            return;
                        }
                        startupUnavailable = false;
                        runtime.activate();
                        configureStartupNotification(runtime, true);
                        platformBridge.info("ShitBotBungee enabled.");
                    }
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
            newRuntime = new ShitBotRuntime(
                    configLoader.load(), consoleSettings, platformBridge);
        } catch (Throwable throwable) {
            platformBridge.error("Unable to reload config", throwable);
            return CompletableFuture.completedFuture(Boolean.FALSE);
        }
        return newRuntime.startAsync().handle(new java.util.function.BiFunction<Void, Throwable, Boolean>() {
            @Override
            public Boolean apply(Void ignored, Throwable throwable) {
                if (throwable != null) {
                    newRuntime.close();
                    platformBridge.error("New runtime failed to initialize; old runtime kept", FutureUtil.unwrap(throwable));
                    return Boolean.FALSE;
                }
                if (stopping || !runtimeReference.compareAndSet(oldRuntime, newRuntime)) {
                    newRuntime.close();
                    return Boolean.FALSE;
                }
                if (oldRuntime != null) {
                    oldRuntime.close();
                }
                platformBridge.configureConsole(consoleSettings);
                newRuntime.activate();
                configureStartupNotification(newRuntime, false);
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

    private synchronized void configureStartupNotification(final ShitBotRuntime runtime,
                                                           boolean initialStart) {
        closeStartupWatch();
        Settings.ServerStartupNotice notice = runtime.getSettings().getOneBot().getServerStartupNotice();
        if (!notice.isEnabled()) {
            return;
        }
        if (startupNoticeTriggered) {
            return;
        }
        final String targetServer = notice.getTargetServer();
        if (targetServer.isEmpty()) {
            if (initialStart) {
                startupNoticeTriggered = true;
                runtime.notifyServerStarted(platformBridge.getPlatformName());
            }
            return;
        }
        startupWatch = platformBridge.watchServerAvailability(
                targetServer, notice.getCheckIntervalSeconds(), new Runnable() {
                    @Override
                    public void run() {
                        synchronized (ShitBotBungee.this) {
                            if (stopping || startupNoticeTriggered
                                    || runtimeReference.get() != runtime) {
                                return;
                            }
                            startupNoticeTriggered = true;
                            runtime.notifyServerStarted(targetServer);
                        }
                    }
                });
    }

    private synchronized void closeStartupWatch() {
        if (startupWatch != null) {
            startupWatch.close();
            startupWatch = null;
        }
    }

    public boolean isStartupUnavailable() {
        return startupUnavailable;
    }

    public ShitBotRuntime getRuntime() {
        return runtimeReference.get();
    }

    public BungeePlatformBridge getPlatformBridge() {
        return platformBridge;
    }

    public UpdateChecker getUpdateChecker() {
        return updateChecker;
    }

    public java.nio.file.Path getPluginJarPath() {
        return getFile().toPath();
    }

    public void sendUpdateNotice(CommandSender sender, UpdateInfo info) {
        if (sender == null || info == null || updateChecker == null) {
            return;
        }
        sender.sendMessage(TextComponent.fromLegacyText("§e[ShitBot] 发现新版本: §f"
                + updateChecker.getCurrentVersion() + " §7-> §a" + info.getLatestVersion()));
        BaseComponent[] link = TextComponent.fromLegacyText("§b§n" + info.getReleaseUrl());
        for (BaseComponent component : link) {
            component.setClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, info.getReleaseUrl()));
            component.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                    TextComponent.fromLegacyText("§7点击打开 Release 页面")));
        }
        sender.sendMessage(link);
    }

    private void startUpdateCheck() {
        updateChecker.checkAsync().whenComplete(new java.util.function.BiConsumer<UpdateInfo, Throwable>() {
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
                    platformBridge.info("ShitBot update available: " + updateChecker.getCurrentVersion()
                            + " -> " + info.getLatestVersion() + " (" + info.getReleaseUrl() + ")");
                }
            }
        });
    }

    private String errorMessage(Throwable throwable) {
        Throwable cause = FutureUtil.unwrap(throwable);
        String message = cause.getMessage();
        return message == null || message.trim().isEmpty()
                ? cause.getClass().getSimpleName()
                : message;
    }

    @Override
    public void onDisable() {
        stopping = true;
        closeStartupWatch();
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
