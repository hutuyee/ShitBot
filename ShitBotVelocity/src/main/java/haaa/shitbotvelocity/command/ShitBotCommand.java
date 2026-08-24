package haaa.shitbotvelocity.command;

import com.velocitypowered.api.command.SimpleCommand;
import haaa.shitbotvelocity.ShitBotVelocity;
import haaa.shitbot.core.database.EasyBotMigrationResult;
import haaa.shitbot.core.runtime.ShitBotRuntime;
import haaa.shitbot.core.service.EasyBotMigrationService;
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
        if (runtime == null) {
            send(invocation, "§cShitBot 尚未初始化。");
            return;
        }
        String[] args = invocation.arguments();
        if (args.length == 0 || "status".equalsIgnoreCase(args[0])) {
            send(invocation, "§aShitBot §7" + runtime.describeStatus());
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
        if ("migrate".equalsIgnoreCase(args[0])) {
            if (args.length < 2 || !"easybot".equalsIgnoreCase(args[1])) {
                send(invocation, "§e用法: /shitbot migrate easybot [EasyBot.db]");
                return;
            }
            final String fileName = args.length >= 3 ? args[2] : EasyBotMigrationService.DEFAULT_FILE_NAME;
            send(invocation, "§e正在异步迁移 EasyBot 绑定数据: §f" + fileName);
            runtime.getEasyBotMigrationService().migrate(fileName).whenComplete(
                    (EasyBotMigrationResult result, Throwable throwable) ->
                            plugin.getPlatformBridge().executeOnPlatformThread(() -> {
                                if (throwable != null) {
                                    send(invocation, "§cEasyBot 迁移失败: §f" + errorMessage(throwable));
                                } else {
                                    send(invocation, "§aEasyBot 迁移完成: §f" + result.describe());
                                }
                            }));
            return;
        }
        if ("image".equalsIgnoreCase(args[0])) {
            runtime.getImageService().renderOnlineImageAsync().whenComplete((bytes, throwable) -> {
                if (throwable != null) {
                    send(invocation, "§c生成失败: " + FutureUtil.unwrap(throwable).getMessage());
                } else {
                    Path path = runtime.getImageService().getOutputPath();
                    send(invocation, "§a在线图片已生成: §f" + path.toAbsolutePath());
                }
            });
            return;
        }
        send(invocation, "§e/shitbot status|reload|image|migrate easybot [EasyBot.db]");
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        String[] args = invocation.arguments();
        if (args.length <= 1) {
            return Arrays.asList("status", "reload", "image", "migrate");
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

    private void send(Invocation invocation, String legacyText) {
        Component component = LegacyComponentSerializer.legacySection().deserialize(legacyText);
        invocation.source().sendMessage(component);
    }
}
