package haaa.shitbotbungee.command;

import haaa.shitbot.core.database.EasyBotMigrationResult;
import haaa.shitbot.core.config.Translations;
import haaa.shitbot.core.console.ConsoleResult;
import haaa.shitbot.core.runtime.ShitBotRuntime;
import haaa.shitbot.core.service.EasyBotMigrationService;
import haaa.shitbot.core.update.UpdateChecker;
import haaa.shitbot.core.update.UpdateInfo;
import haaa.shitbot.core.update.UpdateInstallResult;
import haaa.shitbot.core.update.UpdatePlatform;
import haaa.shitbot.core.util.FutureUtil;
import haaa.shitbot.core.util.TextUtil;
import haaa.shitbotbungee.ShitBotBungee;
import net.md_5.bungee.api.CommandSender;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.plugin.Command;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public final class ShitBotCommand extends Command {
    private final ShitBotBungee plugin;

    public ShitBotCommand(ShitBotBungee plugin) {
        super("shitbot", null, "sbot");
        this.plugin = plugin;
    }

    @Override
    public void execute(final CommandSender sender, String[] args) {
        final ShitBotRuntime runtime = plugin.getRuntime();
        final Translations translations = runtime == null
                ? plugin.getTranslations()
                : runtime.getSettings().getTranslations();
        if (runtime == null) {
            send(sender, translations == null
                    ? "§cShitBot has not initialized yet."
                    : translations.get("admin.not-initialized"));
            return;
        }
        if (args.length == 0 || "status".equalsIgnoreCase(args[0])) {
            send(sender, translations.format("admin.status", "%status%", runtime.describeStatus()));
            return;
        }
        if (!sender.hasPermission("shitbot.admin")) {
            send(sender, TextUtil.color(runtime.getSettings().getMessages().getNoPermission()));
            return;
        }
        if ("reload".equalsIgnoreCase(args[0])) {
            send(sender, TextUtil.color(runtime.getSettings().getMessages().getReloadStarted()));
            plugin.reloadRuntime().whenComplete(new java.util.function.BiConsumer<Boolean, Throwable>() {
                @Override
                public void accept(Boolean success, Throwable throwable) {
                    ShitBotRuntime current = plugin.getRuntime();
                    ShitBotRuntime messageRuntime = current == null ? runtime : current;
                    String message = Boolean.TRUE.equals(success)
                            ? messageRuntime.getSettings().getMessages().getReloadSuccess()
                            : messageRuntime.getSettings().getMessages().getReloadFailed();
                    send(sender, TextUtil.color(message));
                }
            });
            return;
        }
        if ("update".equalsIgnoreCase(args[0])) {
            beginCoordinatedUpdate(sender, translations);
            return;
        }
        if ("migrate".equalsIgnoreCase(args[0])) {
            if (args.length < 2 || !"easybot".equalsIgnoreCase(args[1])) {
                send(sender, translations.get("admin.migration.usage"));
                return;
            }
            final String fileName = args.length >= 3 ? args[2] : EasyBotMigrationService.DEFAULT_FILE_NAME;
            send(sender, translations.format("admin.migration.started", "%file%", fileName));
            runtime.getEasyBotMigrationService().migrate(fileName).whenComplete(
                    new java.util.function.BiConsumer<EasyBotMigrationResult, Throwable>() {
                        @Override
                        public void accept(final EasyBotMigrationResult result, final Throwable throwable) {
                            plugin.getPlatformBridge().executeOnPlatformThread(new Runnable() {
                                @Override
                                public void run() {
                                    if (throwable != null) {
                                        send(sender, translations.format("admin.migration.failed",
                                                "%error%", errorMessage(throwable)));
                                    } else {
                                        send(sender, translations.format("admin.migration.complete",
                                                "%result%", result.describe(translations)));
                                    }
                                }
                            });
                        }
                    });
            return;
        }
        if ("image".equalsIgnoreCase(args[0])) {
            runtime.getImageService().renderOnlineImageAsync().whenComplete(
                    new java.util.function.BiConsumer<byte[], Throwable>() {
                        @Override
                        public void accept(byte[] bytes, Throwable throwable) {
                            if (throwable != null) {
                                send(sender, translations.format("admin.image.failed", "%error%",
                                        String.valueOf(FutureUtil.unwrap(throwable).getMessage())));
                                return;
                            }
                            Path path = runtime.getImageService().getOutputPath();
                            send(sender, translations.format("admin.image.created",
                                    "%path%", path.toAbsolutePath().toString()));
                        }
                    });
            return;
        }
        send(sender, translations.get("admin.help"));
    }

    private String errorMessage(Throwable throwable) {
        Throwable cause = FutureUtil.unwrap(throwable);
        String message = cause.getMessage();
        return message == null || message.trim().isEmpty()
                ? cause.getClass().getSimpleName()
                : message;
    }

    private void beginCoordinatedUpdate(final CommandSender sender,
                                        final Translations translations) {
        final UpdateChecker updateChecker = plugin.getUpdateChecker();
        if (updateChecker == null) {
            send(sender, translations.get("admin.update.not-initialized"));
            return;
        }
        send(sender, translations.get("admin.update.coordinated-checking"));
        updateChecker.checkAsync().whenComplete(
                new java.util.function.BiConsumer<UpdateInfo, Throwable>() {
                    @Override
                    public void accept(final UpdateInfo info, final Throwable throwable) {
                        if (throwable != null) {
                            runOnPlatform(new Runnable() {
                                @Override
                                public void run() {
                                    send(sender, translations.format("admin.update.check-failed",
                                            "%error%", errorMessage(throwable)));
                                }
                            });
                            return;
                        }
                        installProxyAndBackends(sender, updateChecker, info, translations);
                    }
                });
    }

    private void installProxyAndBackends(final CommandSender sender,
                                         UpdateChecker updateChecker,
                                         UpdateInfo info,
                                         final Translations translations) {
        CompletableFuture<UpdateInstallResult> proxyUpdate = updateChecker.installReleaseAsync(
                info, UpdatePlatform.BUNGEE, plugin.getPluginJarPath());
        CompletableFuture<List<ConsoleResult>> backendUpdates =
                plugin.getPlatformBridge().updateAllBackends(info);

        CompletableFuture<Void> proxyHandled = proxyUpdate.handle(
                new java.util.function.BiFunction<UpdateInstallResult, Throwable, Void>() {
                    @Override
                    public Void apply(final UpdateInstallResult result, final Throwable throwable) {
                        runOnPlatform(new Runnable() {
                            @Override
                            public void run() {
                                if (throwable != null) {
                                    send(sender, translations.format("admin.update.proxy-failed",
                                            "%error%", errorMessage(throwable)));
                                } else {
                                    sendInstallResult(sender, result, translations);
                                }
                            }
                        });
                        return null;
                    }
                });
        CompletableFuture<Void> backendsHandled = backendUpdates.handle(
                new java.util.function.BiFunction<List<ConsoleResult>, Throwable, Void>() {
                    @Override
                    public Void apply(final List<ConsoleResult> results, final Throwable throwable) {
                        runOnPlatform(new Runnable() {
                            @Override
                            public void run() {
                                if (throwable != null) {
                                    send(sender, translations.format("admin.update.backends-failed",
                                            "%error%", errorMessage(throwable)));
                                    return;
                                }
                                if (results.isEmpty()) {
                                    send(sender, translations.get("admin.update.no-backends"));
                                    return;
                                }
                                for (ConsoleResult result : results) {
                                    String color = result.isSuccess() ? "§a" : "§c";
                                    send(sender, color + '[' + result.getSource() + "] §f"
                                            + result.getOutput());
                                }
                            }
                        });
                        return null;
                    }
                });
        CompletableFuture.allOf(proxyHandled, backendsHandled).whenComplete(
                new java.util.function.BiConsumer<Void, Throwable>() {
                    @Override
                    public void accept(Void ignored, Throwable throwable) {
                        runOnPlatform(new Runnable() {
                            @Override
                            public void run() {
                                send(sender, translations.get("admin.update.coordinated-complete"));
                            }
                        });
                    }
                });
    }

    private void runOnPlatform(Runnable runnable) {
        plugin.getPlatformBridge().executeOnPlatformThread(runnable);
    }

    private void sendInstallResult(CommandSender sender,
                                   UpdateInstallResult result,
                                   Translations translations) {
        if (result.getStatus() == UpdateInstallResult.Status.UP_TO_DATE) {
            send(sender, translations.format("admin.update.up-to-date",
                    "%version%", result.getLatestVersion()));
            return;
        }
        if (result.getStatus() == UpdateInstallResult.Status.ALREADY_INSTALLED) {
            send(sender, translations.format("admin.update.already-installed-proxy",
                    "%version%", result.getLatestVersion()));
            return;
        }
        send(sender, translations.format("admin.update.installed-proxy",
                "%version%", result.getLatestVersion()));
        send(sender, translations.format("admin.update.current-jar",
                "%path%", String.valueOf(result.getInstalledPath())));
        send(sender, translations.format("admin.update.backup-jar",
                "%path%", String.valueOf(result.getBackupPath())));
        send(sender, translations.get("admin.update.restart-proxy"));
    }

    private void send(CommandSender sender, String message) {
        sender.sendMessage(new TextComponent(TextUtil.color(message)));
    }
}
