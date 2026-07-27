package haaa.shitbotspigot;

import haaa.shitbot.core.config.Settings;
import haaa.shitbot.core.runtime.ShitBotRuntime;
import haaa.shitbot.core.util.FutureUtil;
import haaa.shitbotspigot.command.ShitBotCommand;
import haaa.shitbotspigot.config.SpigotConfigLoader;
import haaa.shitbotspigot.listener.PlayerLoginListener;
import haaa.shitbotspigot.platform.SpigotPlatformBridge;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

public final class ShitBotSpigot extends JavaPlugin {
    private final AtomicReference<ShitBotRuntime> runtimeReference = new AtomicReference<ShitBotRuntime>();
    private volatile boolean startupUnavailable = true;
    private SpigotConfigLoader configLoader;
    private SpigotPlatformBridge platformBridge;

    @Override
    public void onEnable() {
        this.configLoader = new SpigotConfigLoader(this);
        this.platformBridge = new SpigotPlatformBridge(this);
        getServer().getPluginManager().registerEvents(new PlayerLoginListener(this), this);
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
                        platformBridge.info("ShitBotSpigot enabled.");
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

    public SpigotPlatformBridge getPlatformBridge() {
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
