package haaa.shitbotbungee;

import haaa.shitbot.core.config.Settings;
import haaa.shitbot.core.console.ConsoleSettings;
import haaa.shitbot.core.runtime.ShitBotRuntime;
import haaa.shitbot.core.util.FutureUtil;
import haaa.shitbotbungee.command.ShitBotCommand;
import haaa.shitbotbungee.config.BungeeConfigLoader;
import haaa.shitbotbungee.listener.PlayerChatListener;
import haaa.shitbotbungee.listener.PlayerLoginListener;
import haaa.shitbotbungee.platform.BungeePlatformBridge;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.plugin.Plugin;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

public final class ShitBotBungee extends Plugin {
    private final AtomicReference<ShitBotRuntime> runtimeReference = new AtomicReference<ShitBotRuntime>();
    private volatile boolean startupUnavailable = true;
    private volatile boolean stopping;
    private CompletableFuture<Boolean> reloadFuture;
    private BungeeConfigLoader configLoader;
    private BungeePlatformBridge platformBridge;

    @Override
    public void onEnable() {
        stopping = false;
        this.configLoader = new BungeeConfigLoader(this);
        this.platformBridge = new BungeePlatformBridge(this);
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

    public ShitBotRuntime getRuntime() {
        return runtimeReference.get();
    }

    public BungeePlatformBridge getPlatformBridge() {
        return platformBridge;
    }

    @Override
    public void onDisable() {
        stopping = true;
        ShitBotRuntime runtime = runtimeReference.getAndSet(null);
        if (runtime != null) {
            runtime.close();
        }
        if (platformBridge != null) {
            platformBridge.close();
        }
    }
}
