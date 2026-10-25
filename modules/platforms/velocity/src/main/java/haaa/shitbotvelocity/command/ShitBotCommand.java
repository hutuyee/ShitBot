package haaa.shitbotvelocity.command;

import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
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
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
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
        if ("whitelist".equalsIgnoreCase(args[0])) {
            haaa.shitbot.core.service.WhitelistCommand.execute(runtime.getApi(), translations, args)
                    .thenAccept(message -> send(invocation, message));
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
        if ("editor".equalsIgnoreCase(args[0])) {
            send(invocation, translations.get("admin.editor.opening"));
            runtime.getApi().createImageEditorLoginUrl().whenComplete((url, throwable) ->
                    plugin.getPlatformBridge().executeOnPlatformThread(() -> {
                        if (throwable == null) {
                            sendEditorLink(invocation, translations, url);
                        } else {
                            send(invocation, translations.format("admin.editor.failed", "%error%",
                                    errorMessage(throwable)));
                        }
                    }));
            return;
        }
        send(invocation, translations.get("admin.help"));
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        String[] args = invocation.arguments();
        if (args.length <= 1) {
            return Arrays.asList("status", "reload", "update", "image", "editor", "whitelist", "migrate");
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

    private void sendEditorLink(Invocation invocation, Translations translations, String url) {
        String message = translations.format("admin.editor.url", "%url%", url);
        if (!(invocation.source() instanceof Player)) {
            send(invocation, message);
            return;
        }
        String openButton = translations.get("admin.editor.open-button",
                "&a[打开编辑器]", "&a[Open editor]");
        String openHover = translations.get("admin.editor.open-hover",
                "&f点击在浏览器中打开完整登录链接", "&fClick to open the complete login URL in your browser");
        String copyButton = translations.get("admin.editor.copy-button",
                "&b[复制链接到输入框]", "&b[Copy link via chat]");
        String copyHover = translations.get("admin.editor.copy-hover",
                "&f点击填入聊天输入框，按 Ctrl+A、Ctrl+C 复制，无需发送", "&fClick to fill the chat input, then Ctrl+A, Ctrl+C to copy; do not send");
        invocation.source().sendMessage(editorAction(message, openHover,
                ClickEvent.openUrl(url)));
        invocation.source().sendMessage(Component.empty()
                .append(editorAction(openButton,
                        openHover, ClickEvent.openUrl(url)))
                .append(Component.text("  "))
                .append(editorAction(copyButton,
                        copyHover, ClickEvent.suggestCommand(url))));
    }

    private Component editorAction(String label, String hover, ClickEvent action) {
        LegacyComponentSerializer serializer = LegacyComponentSerializer.legacySection();
        return serializer.deserialize(TextUtil.color(label)).clickEvent(action)
                .hoverEvent(HoverEvent.showText(serializer.deserialize(TextUtil.color(hover))));
    }

    private void send(Invocation invocation, String legacyText) {
        Component component = LegacyComponentSerializer.legacySection().deserialize(TextUtil.color(legacyText));
        invocation.source().sendMessage(component);
    }
}
