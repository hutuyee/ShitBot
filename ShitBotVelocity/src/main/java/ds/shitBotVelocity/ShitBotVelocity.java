package ds.shitBotVelocity;

import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import ds.shitBotVelocity.command.ShitBotCommand;
import ds.shitBotVelocity.config.VelocityConfigLoader;
import ds.shitBotVelocity.listener.PlayerChatListener;
import ds.shitBotVelocity.listener.PlayerLoginListener;
import ds.shitBotVelocity.platform.VelocityPlatformBridge;
import haaa.shitbot.core.config.Settings;
import haaa.shitbot.core.runtime.ShitBotRuntime;
import haaa.shitbot.core.util.FutureUtil;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

public final class ShitBotVelocity {
    private final ProxyServer server;
    private final Logger logger;
    private final Path dataDirectory;
    private final AtomicReference<ShitBotRuntime> runtimeReference = new AtomicReference<ShitBotRuntime>();
    private volatile boolean startupUnavailable = true;
    private VelocityConfigLoader configLoader;
    private VelocityPlatformBridge platformBridge;

    @Inject
    public ShitBotVelocity(ProxyServer server, Logger logger, @DataDirectory Path dataDirectory) {
        this.server = server;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onProxyInitialization(ProxyInitializeEvent event) {
        this.configLoader = new VelocityConfigLoader(dataDirectory, getClass().getClassLoader());
        this.platformBridge = new VelocityPlatformBridge(server, logger, dataDirectory);
        server.getEventManager().register(this, new PlayerLoginListener(this));
        server.getEventManager().register(this, new PlayerChatListener(this));
        CommandMeta commandMeta = server.getCommandManager().metaBuilder("shitbot")
                .aliases("sbot")
                .build();
        server.getCommandManager().register(commandMeta, new ShitBotCommand(this));

        try {
            Settings settings = configLoader.load();
            ShitBotRuntime runtime = new ShitBotRuntime(settings, platformBridge);
            runtimeReference.set(runtime);
            startupUnavailable = false;
            runtime.startAsync().whenComplete((ignored, throwable) -> {
                if (throwable != null) {
                    runtime.close();
                    platformBridge.error("ShitBot failed to start", FutureUtil.unwrap(throwable));
                } else {
                    runtime.activate();
                    platformBridge.info("ShitBotVelocity enabled.");
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
        return newRuntime.startAsync().handle((ignored, throwable) -> {
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
        });
    }

    public boolean isStartupUnavailable() {
        return startupUnavailable;
    }

    public ShitBotRuntime getRuntime() {
        return runtimeReference.get();
    }

    public VelocityPlatformBridge getPlatformBridge() {
        return platformBridge;
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        ShitBotRuntime runtime = runtimeReference.getAndSet(null);
        if (runtime != null) {
            runtime.close();
        }
    }
}
