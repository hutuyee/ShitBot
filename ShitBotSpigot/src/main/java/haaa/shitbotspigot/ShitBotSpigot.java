package haaa.shitbotspigot;

import haaa.shitbot.core.config.Settings;
import haaa.shitbot.core.console.ConsoleSettings;
import haaa.shitbot.core.runtime.ShitBotRuntime;
import haaa.shitbot.core.update.UpdateChecker;
import haaa.shitbot.core.update.UpdateInfo;
import haaa.shitbot.core.util.FutureUtil;
import haaa.shitbotspigot.command.ShitBotCommand;
import haaa.shitbotspigot.config.SpigotConfigLoader;
import haaa.shitbotspigot.listener.PlayerChatListener;
import haaa.shitbotspigot.listener.PlayerInventorySnapshotListener;
import haaa.shitbotspigot.listener.PlayerLoginListener;
import haaa.shitbotspigot.platform.SpigotPlatformBridge;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

public final class ShitBotSpigot extends JavaPlugin {
    private final AtomicReference<ShitBotRuntime> runtimeReference = new AtomicReference<ShitBotRuntime>();
    private volatile boolean startupUnavailable = true;
    private volatile boolean backendMode;
    private volatile boolean stopping;
    private CompletableFuture<Boolean> reloadFuture;
    private SpigotConfigLoader configLoader;
    private SpigotPlatformBridge platformBridge;
    private UpdateChecker updateChecker;

    @Override
    public void onEnable() {
        stopping = false;
        this.configLoader = new SpigotConfigLoader(this);
        this.platformBridge = new SpigotPlatformBridge(this);
        this.updateChecker = new UpdateChecker(getDescription().getVersion(), platformBridge);
        startUpdateCheck();
        getServer().getPluginManager().registerEvents(new PlayerLoginListener(this), this);
        getServer().getPluginManager().registerEvents(new PlayerChatListener(this), this);
        getServer().getPluginManager().registerEvents(new PlayerInventorySnapshotListener(this), this);
        ShitBotCommand commandHandler = new ShitBotCommand(this);
        PluginCommand command = getCommand("shitbot");
        if (command != null) {
            command.setExecutor(commandHandler);
            command.setTabCompleter(commandHandler);
        } else {
            getLogger().severe("Command 'shitbot' is missing from plugin.yml");
        }

        try {
            Settings settings = configLoader.load();
            ConsoleSettings consoleSettings = configLoader.loadConsoleSettings();
            final boolean backendMode = configLoader.isBackendMode();
            platformBridge.configureConsole(consoleSettings, backendMode);
            this.backendMode = backendMode;
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
                        if (!backendMode) {
                            runtime.activate();
                        }
                        platformBridge.info("ShitBotSpigot enabled (role="
                                + (backendMode ? "backend" : "standalone") + ").");
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
        final boolean configuredBackendMode;
        final ConsoleSettings consoleSettings;
        try {
            Settings settings = configLoader.load();
            consoleSettings = configLoader.loadConsoleSettings();
            newRuntime = new ShitBotRuntime(settings, consoleSettings, platformBridge);
            configuredBackendMode = configLoader.isBackendMode();
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
                try {
                    platformBridge.configureConsole(consoleSettings, configuredBackendMode);
                } catch (Throwable listenerFailure) {
                    if (runtimeReference.compareAndSet(newRuntime, oldRuntime)) {
                        newRuntime.close();
                    }
                    platformBridge.error("Console listener reload failed; old runtime kept",
                            listenerFailure);
                    return Boolean.FALSE;
                }
                if (oldRuntime != null) {
                    oldRuntime.close();
                }
                ShitBotSpigot.this.backendMode = configuredBackendMode;
                if (!configuredBackendMode) {
                    newRuntime.activate();
                }
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

    public boolean isStartupUnavailable() {
        return startupUnavailable;
    }

    public boolean isBackendMode() {
        return backendMode;
    }

    public ShitBotRuntime getRuntime() {
        return runtimeReference.get();
    }

    public SpigotPlatformBridge getPlatformBridge() {
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
        sender.sendMessage("§e[ShitBot] 发现新版本: §f" + updateChecker.getCurrentVersion()
                + " §7-> §a" + info.getLatestVersion());
        BaseComponent[] link = TextComponent.fromLegacyText("§b§n" + info.getReleaseUrl());
        for (BaseComponent component : link) {
            component.setClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, info.getReleaseUrl()));
            component.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                    TextComponent.fromLegacyText("§7点击打开 Release 页面")));
        }
        sender.spigot().sendMessage(link);
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
