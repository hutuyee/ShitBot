package haaa.shitbotbungee;

import haaa.shitbot.core.config.Settings;
import haaa.shitbot.core.runtime.ShitBotRuntime;
import haaa.shitbot.core.util.FutureUtil;
import haaa.shitbotbungee.command.ShitBotCommand;
import haaa.shitbotbungee.config.BungeeConfigLoader;
import haaa.shitbotbungee.listener.PlayerLoginListener;
import haaa.shitbotbungee.platform.BungeePlatformBridge;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.plugin.Plugin;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

public final class ShitBotBungee extends Plugin {
    private final AtomicReference<ShitBotRuntime> runtimeReference = new AtomicReference<ShitBotRuntime>();
    private volatile boolean startupUnavailable = true;
    private BungeeConfigLoader configLoader;
    private BungeePlatformBridge platformBridge;

    @Override
    public void onEnable() {
        this.configLoader = new BungeeConfigLoader(this);
        this.platformBridge = new BungeePlatformBridge(this);
        ProxyServer.getInstance().getPluginManager().registerListener(this, new PlayerLoginListener(this));
        ProxyServer.getInstance().getPluginManager().registerCommand(this, new ShitBotCommand(this));

        try {
            Settings settings = configLoader.load();
            ShitBotRuntime runtime = new ShitBotRuntime(settings, platformBridge);
            runtimeReference.set(runtime);
            startupUnavailable = false;
            runtime.startAsync().whenComplete(new java.util.function.BiConsumer<Void, Throwable>() {
                @Override
                public void accept(Void ignored, Throwable throwable) {
                    if (throwable != null) {
                        runtime.close();
                        platformBridge.error("ShitBot failed to start", FutureUtil.unwrap(throwable));
                    } else {
                        runtime.activate();
                        platformBridge.info("ShitBotBungee enabled.");
                    }
                }
            });
        } catch (Throwable throwable) {
            platformBridge.error("Unable to load ShitBot config", throwable);
        }
    }

    public CompletableFuture<Boolean> reloadRuntime() {
        final ShitBotRuntime oldRuntime = runtimeReference.get();
        final ShitBotRuntime newRuntime;
        try {
            newRuntime = new ShitBotRuntime(configLoader.load(), platformBridge);
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
                runtimeReference.set(newRuntime);
                if (oldRuntime != null) {
                    oldRuntime.close();
                }
                newRuntime.activate();
                return Boolean.TRUE;
            }
        });
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
        ShitBotRuntime runtime = runtimeReference.getAndSet(null);
        if (runtime != null) {
            runtime.close();
        }
    }
}
