package haaa.shitbotvelocity;

import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import haaa.shitbotvelocity.command.ShitBotCommand;
import haaa.shitbotvelocity.config.VelocityConfigLoader;
import haaa.shitbotvelocity.listener.PlayerChatListener;
import haaa.shitbotvelocity.listener.PlayerLoginListener;
import haaa.shitbotvelocity.platform.VelocityPlatformBridge;
import haaa.shitbot.core.config.Settings;
import haaa.shitbot.core.config.Translations;
import haaa.shitbot.core.console.ConsoleSettings;
import haaa.shitbot.core.platform.PlatformBridge;
import haaa.shitbot.core.runtime.ShitBotRuntime;
import haaa.shitbot.core.update.UpdateChecker;
import haaa.shitbot.core.update.UpdateInfo;
import haaa.shitbot.core.util.FutureUtil;
import haaa.shitbot.core.util.TextUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bstats.velocity.Metrics;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

public final class ShitBotVelocity {
    private static final int BSTATS_PLUGIN_ID = 33868;
    private final ProxyServer server;
    private final Logger logger;
    private final Path dataDirectory;
    private final Metrics.Factory metricsFactory;
    private final AtomicReference<ShitBotRuntime> runtimeReference = new AtomicReference<ShitBotRuntime>();
    private volatile boolean startupUnavailable = true;
    private volatile Translations translations;
    private volatile boolean stopping;
    private CompletableFuture<Boolean> reloadFuture;
    private VelocityConfigLoader configLoader;
    private VelocityPlatformBridge platformBridge;
    private UpdateChecker updateChecker;
    private Path pluginJarPath;
    private PlatformBridge.ServerAvailabilityWatch startupWatch;
    private boolean startupNoticeTriggered;

    @Inject
    public ShitBotVelocity(ProxyServer server,
                          Logger logger,
                          @DataDirectory Path dataDirectory,
                          Metrics.Factory metricsFactory) {
        this.server = server;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
        this.metricsFactory = metricsFactory;
    }

    @Subscribe
    public void onProxyInitialization(ProxyInitializeEvent event) {
        stopping = false;
        startupNoticeTriggered = false;
        this.configLoader = new VelocityConfigLoader(dataDirectory, getClass().getClassLoader());
        this.platformBridge = new VelocityPlatformBridge(this, server, logger, dataDirectory);
        this.pluginJarPath = resolvePluginPath();
        this.updateChecker = new UpdateChecker(resolvePluginVersion(), platformBridge);
        startUpdateCheck();
        server.getEventManager().register(this, new PlayerLoginListener(this));
        server.getEventManager().register(this, new PlayerChatListener(this));
        CommandMeta commandMeta = server.getCommandManager().metaBuilder("shitbot")
                .aliases("sbot")
                .build();
        server.getCommandManager().register(commandMeta, new ShitBotCommand(this));

        try {
            Settings settings = configLoader.load();
            translations = settings.getTranslations();
            if (configLoader.isBStatsEnabled()) {
                metricsFactory.make(this, BSTATS_PLUGIN_ID);
            }
            ConsoleSettings consoleSettings = configLoader.loadConsoleSettings();
            platformBridge.configureConsole(consoleSettings);
            ShitBotRuntime runtime = new ShitBotRuntime(settings, consoleSettings, platformBridge);
            runtimeReference.set(runtime);
            runtime.startAsync().whenComplete((ignored, throwable) -> {
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
                    platformBridge.info("ShitBotVelocity enabled.");
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
        created.whenComplete((ignored, throwable) -> clearReloadFuture(created));
        return created;
    }

    private CompletableFuture<Boolean> reloadRuntimeInternal() {
        final ShitBotRuntime oldRuntime = runtimeReference.get();
        final ShitBotRuntime newRuntime;
        final ConsoleSettings consoleSettings;
        try {
            consoleSettings = configLoader.loadConsoleSettings();
            Settings settings = configLoader.load();
            newRuntime = new ShitBotRuntime(settings, consoleSettings, platformBridge);
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
            if (stopping || !runtimeReference.compareAndSet(oldRuntime, newRuntime)) {
                newRuntime.close();
                return Boolean.FALSE;
            }
            if (oldRuntime != null) {
                oldRuntime.close();
            }
            platformBridge.configureConsole(consoleSettings);
            ShitBotVelocity.this.translations = newRuntime.getSettings().getTranslations();
            newRuntime.activate();
            configureStartupNotification(newRuntime, false);
            startupUnavailable = false;
            return Boolean.TRUE;
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
                targetServer, notice.getCheckIntervalSeconds(), () -> {
                    synchronized (ShitBotVelocity.this) {
                        if (stopping || startupNoticeTriggered
                                || runtimeReference.get() != runtime) {
                            return;
                        }
                        startupNoticeTriggered = true;
                        runtime.notifyServerStarted(targetServer);
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

    public Translations getTranslations() {
        return translations;
    }

    public VelocityPlatformBridge getPlatformBridge() {
        return platformBridge;
    }

    public UpdateChecker getUpdateChecker() {
        return updateChecker;
    }

    public Path getPluginJarPath() {
        return pluginJarPath;
    }

    public void sendUpdateNotice(CommandSource sender, UpdateInfo info) {
        if (sender == null || info == null || updateChecker == null) {
            return;
        }
        ShitBotRuntime runtime = runtimeReference.get();
        if (runtime == null) {
            return;
        }
        Translations translations = runtime.getSettings().getTranslations();
        LegacyComponentSerializer serializer = LegacyComponentSerializer.legacySection();
        sender.sendMessage(serializer.deserialize(TextUtil.color(translations.format(
                "admin.update.notification",
                "%current%", updateChecker.getCurrentVersion(),
                "%latest%", info.getLatestVersion()))));
        Component link = serializer.deserialize("§b§n" + info.getReleaseUrl())
                .clickEvent(ClickEvent.openUrl(info.getReleaseUrl()))
                .hoverEvent(HoverEvent.showText(serializer.deserialize(TextUtil.color(
                        translations.get("admin.update.notification-hover")))));
        sender.sendMessage(link);
    }

    private void startUpdateCheck() {
        updateChecker.checkAsync().whenComplete((info, throwable) -> {
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
        });
    }

    private String resolvePluginVersion() {
        return server.getPluginManager().getPlugin("shitbotvelocity")
                .flatMap(container -> container.getDescription().getVersion())
                .orElse("unknown");
    }

    private Path resolvePluginPath() {
        return server.getPluginManager().getPlugin("shitbotvelocity")
                .flatMap(container -> container.getDescription().getSource())
                .orElse(null);
    }

    private String errorMessage(Throwable throwable) {
        Throwable cause = FutureUtil.unwrap(throwable);
        String message = cause.getMessage();
        return message == null || message.trim().isEmpty()
                ? cause.getClass().getSimpleName()
                : message;
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
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
