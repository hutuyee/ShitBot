package haaa.shitbotspigot.command;

import haaa.shitbot.core.database.EasyBotMigrationResult;
import haaa.shitbot.core.config.Translations;
import haaa.shitbot.core.runtime.ShitBotRuntime;
import haaa.shitbot.core.service.EasyBotMigrationService;
import haaa.shitbot.core.update.UpdateChecker;
import haaa.shitbot.core.update.UpdateInstallResult;
import haaa.shitbot.core.update.UpdatePlatform;
import haaa.shitbot.core.util.FutureUtil;
import haaa.shitbot.core.util.TextUtil;
import haaa.shitbotspigot.ShitBotSpigot;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public final class ShitBotCommand implements CommandExecutor, TabCompleter {
    private final ShitBotSpigot plugin;

    public ShitBotCommand(ShitBotSpigot plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(final CommandSender sender, Command command, String label, String[] args) {
        final ShitBotRuntime runtime = plugin.getRuntime();
        final Translations translations = runtime == null
                ? plugin.getTranslations()
                : runtime.getSettings().getTranslations();
        if (runtime == null) {
            send(sender, translations == null
                    ? "§cShitBot has not initialized yet."
                    : translations.get("admin.not-initialized"));
            return true;
        }
        if (args.length == 0 || "status".equalsIgnoreCase(args[0])) {
            send(sender, translations.format("admin.status", "%status%", runtime.describeStatus()));
            return true;
        }
        if (!sender.hasPermission("shitbot.admin")) {
            sender.sendMessage(TextUtil.color(runtime.getSettings().getMessages().getNoPermission()));
            return true;
        }
        if ("reload".equalsIgnoreCase(args[0])) {
            sender.sendMessage(TextUtil.color(runtime.getSettings().getMessages().getReloadStarted()));
            plugin.reloadRuntime().whenComplete(new java.util.function.BiConsumer<Boolean, Throwable>() {
                @Override
                public void accept(final Boolean success, Throwable throwable) {
                    plugin.getPlatformBridge().executeOnSenderThread(sender, new Runnable() {
                        @Override
                        public void run() {
                            ShitBotRuntime current = plugin.getRuntime();
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
            final UpdateChecker updateChecker = plugin.getUpdateChecker();
            if (updateChecker == null) {
                send(sender, translations.get("admin.update.not-initialized"));
                return true;
            }
            send(sender, translations.get("admin.update.checking"));
            updateChecker.updateAsync(UpdatePlatform.SPIGOT, plugin.getPluginJarPath()).whenComplete(
                    new java.util.function.BiConsumer<UpdateInstallResult, Throwable>() {
                        @Override
                        public void accept(final UpdateInstallResult result, final Throwable throwable) {
                            plugin.getPlatformBridge().executeOnSenderThread(sender, new Runnable() {
                                @Override
                                public void run() {
                                    if (throwable != null) {
                                        send(sender, translations.format("admin.update.failed",
                                                "%error%", errorMessage(throwable)));
                                        return;
                                    }
                                    sendInstallResult(sender, result, translations);
                                }
                            });
                        }
                    });
            return true;
        }
        if ("migrate".equalsIgnoreCase(args[0])) {
            if (args.length < 2 || !"easybot".equalsIgnoreCase(args[1])) {
                send(sender, translations.get("admin.migration.usage"));
                return true;
            }
            final String fileName = args.length >= 3 ? args[2] : EasyBotMigrationService.DEFAULT_FILE_NAME;
            send(sender, translations.format("admin.migration.started", "%file%", fileName));
            runtime.getEasyBotMigrationService().migrate(fileName).whenComplete(
                    new java.util.function.BiConsumer<EasyBotMigrationResult, Throwable>() {
                        @Override
                        public void accept(final EasyBotMigrationResult result, final Throwable throwable) {
                            plugin.getPlatformBridge().executeOnSenderThread(sender, new Runnable() {
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
            return true;
        }
        if ("image".equalsIgnoreCase(args[0])) {
            runtime.getImageService().renderOnlineImageAsync().whenComplete(
                    new java.util.function.BiConsumer<byte[], Throwable>() {
                        @Override
                        public void accept(byte[] bytes, final Throwable throwable) {
                            plugin.getPlatformBridge().executeOnSenderThread(sender, new Runnable() {
                                @Override
                                public void run() {
                                    if (throwable != null) {
                                        send(sender, translations.format("admin.image.failed", "%error%",
                                                String.valueOf(FutureUtil.unwrap(throwable).getMessage())));
                                    } else {
                                        Path path = runtime.getImageService().getOutputPath();
                                        send(sender, translations.format("admin.image.created",
                                                "%path%", path.toAbsolutePath().toString()));
                                    }
                                }
                            });
                        }
                    });
            return true;
        }
        send(sender, translations.get("admin.help"));
        return true;
    }

    private String errorMessage(Throwable throwable) {
        Throwable cause = FutureUtil.unwrap(throwable);
        String message = cause.getMessage();
        return message == null || message.trim().isEmpty()
                ? cause.getClass().getSimpleName()
                : message;
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
            send(sender, translations.format("admin.update.already-installed-server",
                    "%version%", result.getLatestVersion()));
            return;
        }
        send(sender, translations.format("admin.update.installed-server",
                "%version%", result.getLatestVersion()));
        send(sender, translations.format("admin.update.current-jar",
                "%path%", String.valueOf(result.getInstalledPath())));
        send(sender, translations.format("admin.update.backup-jar",
                "%path%", String.valueOf(result.getBackupPath())));
        send(sender, translations.get("admin.update.restart-server"));
    }

    private void send(CommandSender sender, String message) {
        sender.sendMessage(TextUtil.color(message));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return Arrays.asList("status", "reload", "update", "image", "migrate");
        }
        if (args.length == 2 && "migrate".equalsIgnoreCase(args[0])) {
            return Collections.singletonList("easybot");
        }
        if (args.length == 3
                && "migrate".equalsIgnoreCase(args[0])
                && "easybot".equalsIgnoreCase(args[1])) {
            return Collections.singletonList(EasyBotMigrationService.DEFAULT_FILE_NAME);
        }
        return Collections.emptyList();
    }
}
