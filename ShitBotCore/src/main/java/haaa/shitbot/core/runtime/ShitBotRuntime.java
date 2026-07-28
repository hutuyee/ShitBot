package haaa.shitbot.core.runtime;

import haaa.shitbot.core.config.Settings;
import haaa.shitbot.core.database.BindingRepository;
import haaa.shitbot.core.database.DatabaseManager;
import haaa.shitbot.core.onebot.OneBotClient;
import haaa.shitbot.core.onebot.OneBotCommandHandler;
import haaa.shitbot.core.platform.PlatformBridge;
import haaa.shitbot.core.service.BindingService;
import haaa.shitbot.core.service.EasyBotMigrationService;
import haaa.shitbot.core.service.LoginDecision;
import haaa.shitbot.core.service.OnlineImageService;
import haaa.shitbot.core.util.FutureUtil;
import haaa.shitbot.core.util.NamedThreadFactory;
import haaa.shitbot.core.util.TextUtil;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** A complete, independently reloadable ShitBot runtime. */
public final class ShitBotRuntime implements AutoCloseable {
    private final Settings settings;
    private final PlatformBridge platform;
    private final DatabaseManager database;
    private final BindingRepository repository;
    private final BindingService bindingService;
    private final EasyBotMigrationService easyBotMigrationService;
    private final OnlineImageService imageService;
    private final OneBotClient oneBotClient;
    private final OneBotCommandHandler commandHandler;
    private final ScheduledExecutorService maintenanceExecutor = Executors.newSingleThreadScheduledExecutor(
            new NamedThreadFactory("shitbot-maintenance", true));
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean activated = new AtomicBoolean();
    private volatile CompletableFuture<Void> startFuture;

    public ShitBotRuntime(Settings settings, PlatformBridge platform) {
        this.settings = settings;
        this.platform = platform;
        this.database = new DatabaseManager(settings.getDatabase(), platform);
        this.repository = new BindingRepository(database, settings.getBinding());
        this.bindingService = new BindingService(settings, repository);
        this.easyBotMigrationService = new EasyBotMigrationService(platform, repository);
        this.imageService = new OnlineImageService(settings.getImage(), platform);
        this.oneBotClient = new OneBotClient(settings.getOneBot(), platform);
        this.commandHandler = new OneBotCommandHandler(
                settings, platform, bindingService, imageService, oneBotClient);
        this.oneBotClient.setGroupMessageConsumer(new java.util.function.Consumer<haaa.shitbot.core.onebot.GroupMessage>() {
            @Override
            public void accept(haaa.shitbot.core.onebot.GroupMessage message) {
                commandHandler.handle(message);
            }
        });
    }

    public synchronized CompletableFuture<Void> startAsync() {
        if (startFuture != null) {
            return startFuture;
        }
        if (closed.get()) {
            return FutureUtil.failedFuture(new IllegalStateException("Runtime is closed"));
        }
        startFuture = database.initializeAsync().thenRun(new Runnable() {
            @Override
            public void run() {
                if (closed.get()) {
                    return;
                }
                maintenanceExecutor.scheduleAtFixedRate(new Runnable() {
                    @Override
                    public void run() {
                        repository.cleanupExpiredCodes().exceptionally(
                                new java.util.function.Function<Throwable, Integer>() {
                                    @Override
                                    public Integer apply(Throwable throwable) {
                                        platform.warn("Failed to clean expired bind codes: "
                                                + FutureUtil.unwrap(throwable).getMessage());
                                        return Integer.valueOf(0);
                                    }
                                });
                    }
                }, 10L, 10L, TimeUnit.MINUTES);
            }
        });
        return startFuture;
    }

    public CompletableFuture<LoginDecision> checkLogin(String playerName, String playerUuid) {
        CompletableFuture<Void> currentStart = startFuture;
        if (closed.get() || currentStart == null || !database.isReady()) {
            return CompletableFuture.completedFuture(LoginDecision.deny(
                    TextUtil.color(settings.getMessages().getKickDatabaseUnavailable())));
        }
        final CompletableFuture<LoginDecision> result = new CompletableFuture<LoginDecision>();
        final java.util.concurrent.ScheduledFuture<?> timeoutTask;
        try {
            timeoutTask = maintenanceExecutor.schedule(new Runnable() {
                @Override
                public void run() {
                    result.complete(LoginDecision.deny(
                            TextUtil.color(settings.getMessages().getKickDatabaseUnavailable())));
                }
            }, settings.getBinding().getLoginDatabaseTimeoutSeconds(), TimeUnit.SECONDS);
        } catch (java.util.concurrent.RejectedExecutionException exception) {
            return CompletableFuture.completedFuture(LoginDecision.deny(
                    TextUtil.color(settings.getMessages().getKickDatabaseUnavailable())));
        }
        bindingService.checkLogin(playerName, playerUuid).whenComplete(
                new java.util.function.BiConsumer<LoginDecision, Throwable>() {
                    @Override
                    public void accept(LoginDecision decision, Throwable throwable) {
                        timeoutTask.cancel(false);
                        if (throwable != null || decision == null) {
                            result.complete(LoginDecision.deny(
                                    TextUtil.color(settings.getMessages().getKickDatabaseUnavailable())));
                        } else {
                            result.complete(decision);
                        }
                    }
                });
        return result;
    }

    /** Starts external communication after this runtime has become the active instance. */
    public void activate() {
        if (!closed.get() && isReady() && activated.compareAndSet(false, true)) {
            oneBotClient.start();
        }
    }

    public Settings getSettings() {
        return settings;
    }

    public BindingService getBindingService() {
        return bindingService;
    }

    public EasyBotMigrationService getEasyBotMigrationService() {
        return easyBotMigrationService;
    }

    public OnlineImageService getImageService() {
        return imageService;
    }

    public OneBotClient getOneBotClient() {
        return oneBotClient;
    }

    public boolean isReady() {
        CompletableFuture<Void> current = startFuture;
        return current != null && current.isDone() && !current.isCompletedExceptionally()
                && database.isReady() && !closed.get();
    }

    public String describeStatus() {
        return "ready=" + isReady()
                + ", database=" + settings.getDatabase().getType().name().toLowerCase(java.util.Locale.ROOT)
                + ", onebot=" + (settings.getOneBot().isEnabled()
                ? (oneBotClient.isConnected() ? "connected" : "disconnected")
                : "disabled");
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        maintenanceExecutor.shutdownNow();
        oneBotClient.close();
        imageService.close();
        database.close();
    }
}
