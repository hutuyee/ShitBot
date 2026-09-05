package haaa.shitbotvelocity.command;

import com.velocitypowered.api.command.SimpleCommand;
import haaa.shitbotvelocity.ShitBotVelocity;
import haaa.shitbot.core.config.Translations;
import haaa.shitbot.core.database.EasyBotMigrationResult;
import haaa.shitbot.core.runtime.ShitBotRuntime;
import haaa.shitbot.core.service.EasyBotMigrationService;
import haaa.shitbot.core.update.UpdateChecker;
import haaa.shitbot.core.update.UpdateInstallResult;
import haaa.shitbot.core.update.UpdatePlatform;
import haaa.shitbot.core.util.FutureUtil;
import haaa.shitbot.core.util.TextUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public final class ShitBotCommand implements SimpleCommand {
    private final ShitBotVelocity plugin;

    public ShitBotCommand(ShitBotVelocity plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(final Invocation invocation) {
        final ShitBotRuntime runtime = plugin.getRuntime();
        final Translations translations = runtime == null
                ? plugin.getTranslations()
                : runtime.getSettings().getTranslations();
        if (runtime == null) {
            send(invocation, translations == null
                    ? "§cShitBot has not initialized yet."
                    : translations.get("admin.not-initialized"));
            return;
        }
        String[] args = invocation.arguments();
        if (args.length == 0 || "status".equalsIgnoreCase(args[0])) {
            send(invocation, translations.format("admin.status", "%status%", runtime.describeStatus()));
            return;
        }
        if (!invocation.source().hasPermission("shitbot.admin")) {
            send(invocation, TextUtil.color(runtime.getSettings().getMessages().getNoPermission()));
            return;
        }
        if ("reload".equalsIgnoreCase(args[0])) {
            send(invocation, TextUtil.color(runtime.getSettings().getMessages().getReloadStarted()));
            plugin.reloadRuntime().whenComplete((success, throwable) -> {
                ShitBotRuntime current = plugin.getRuntime();
                ShitBotRuntime messageRuntime = current == null ? runtime : current;
                String message = Boolean.TRUE.equals(success)
                        ? messageRuntime.getSettings().getMessages().getReloadSuccess()
                        : messageRuntime.getSettings().getMessages().getReloadFailed();
                send(invocation, TextUtil.color(message));
            });
            return;
        }
        if ("update".equalsIgnoreCase(args[0])) {
            final UpdateChecker updateChecker = plugin.getUpdateChecker();
            if (updateChecker == null) {
                send(invocation, translations.get("admin.update.not-initialized"));
                return;
            }
            send(invocation, translations.get("admin.update.checking"));
            updateChecker.updateAsync(UpdatePlatform.VELOCITY, plugin.getPluginJarPath())
                    .whenComplete((UpdateInstallResult result, Throwable throwable) ->
                    plugin.getPlatformBridge().executeOnPlatformThread(() -> {
                        if (throwable != null) {
                            send(invocation, translations.format("admin.update.failed",
                                    "%error%", errorMessage(throwable)));
                            return;
                        }
                        sendInstallResult(invocation, result, translations);
                    }));
            return;
        }
        if ("migrate".equalsIgnoreCase(args[0])) {
            if (args.length < 2 || !"easybot".equalsIgnoreCase(args[1])) {
                send(invocation, translations.get("admin.migration.usage"));
                return;
            }
            final String fileName = args.length >= 3 ? args[2] : EasyBotMigrationService.DEFAULT_FILE_NAME;
            send(invocation, translations.format("admin.migration.started", "%file%", fileName));
            runtime.getEasyBotMigrationService().migrate(fileName).whenComplete(
                    (EasyBotMigrationResult result, Throwable throwable) ->
                            plugin.getPlatformBridge().executeOnPlatformThread(() -> {
                                if (throwable != null) {
                                    send(invocation, translations.format("admin.migration.failed",
                                            "%error%", errorMessage(throwable)));
                                } else {
                                    send(invocation, translations.format("admin.migration.complete",
                                            "%result%", result.describe(translations)));
                                }
                            }));
            return;
        }
        if ("image".equalsIgnoreCase(args[0])) {
            runtime.getImageService().renderOnlineImageAsync().whenComplete((bytes, throwable) -> {
                if (throwable != null) {
                    send(invocation, translations.format("admin.image.failed", "%error%",
                            String.valueOf(FutureUtil.unwrap(throwable).getMessage())));
                } else {
                    Path path = runtime.getImageService().getOutputPath();
                    send(invocation, translations.format("admin.image.created",
                            "%path%", path.toAbsolutePath().toString()));
                }
            });
            return;
        }
        send(invocation, translations.get("admin.help"));
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        String[] args = invocation.arguments();
        if (args.length <= 1) {
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

    @Override
    public CompletableFuture<List<String>> suggestAsync(Invocation invocation) {
        return CompletableFuture.completedFuture(suggest(invocation));
    }

    private String errorMessage(Throwable throwable) {
        Throwable cause = FutureUtil.unwrap(throwable);
        String message = cause.getMessage();
        return message == null || message.trim().isEmpty()
                ? cause.getClass().getSimpleName()
                : message;
    }

    private void sendInstallResult(Invocation invocation,
                                   UpdateInstallResult result,
                                   Translations translations) {
        if (result.getStatus() == UpdateInstallResult.Status.UP_TO_DATE) {
            send(invocation, translations.format("admin.update.up-to-date",
                    "%version%", result.getLatestVersion()));
            return;
        }
        if (result.getStatus() == UpdateInstallResult.Status.ALREADY_INSTALLED) {
            send(invocation, translations.format("admin.update.already-installed-proxy",
                    "%version%", result.getLatestVersion()));
            return;
        }
        send(invocation, translations.format("admin.update.installed-proxy",
                "%version%", result.getLatestVersion()));
        send(invocation, translations.format("admin.update.current-jar",
                "%path%", String.valueOf(result.getInstalledPath())));
        send(invocation, translations.format("admin.update.backup-jar",
                "%path%", String.valueOf(result.getBackupPath())));
        send(invocation, translations.get("admin.update.restart-proxy"));
    }

    private void send(Invocation invocation, String legacyText) {
        Component component = LegacyComponentSerializer.legacySection().deserialize(TextUtil.color(legacyText));
        invocation.source().sendMessage(component);
    }
}
