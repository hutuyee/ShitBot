package haaa.shitbotbungee.command;

import haaa.shitbot.core.database.EasyBotMigrationResult;
import haaa.shitbot.core.runtime.ShitBotRuntime;
import haaa.shitbot.core.service.EasyBotMigrationService;
import haaa.shitbot.core.update.UpdateChecker;
import haaa.shitbot.core.update.UpdateInfo;
import haaa.shitbot.core.util.FutureUtil;
import haaa.shitbot.core.util.TextUtil;
import haaa.shitbotbungee.ShitBotBungee;
import net.md_5.bungee.api.CommandSender;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.plugin.Command;

import java.nio.file.Path;

public final class ShitBotCommand extends Command {
    private final ShitBotBungee plugin;

    public ShitBotCommand(ShitBotBungee plugin) {
        super("shitbot", null, "sbot");
        this.plugin = plugin;
    }

    @Override
    public void execute(final CommandSender sender, String[] args) {
        final ShitBotRuntime runtime = plugin.getRuntime();
        if (runtime == null) {
            send(sender, "§cShitBot 尚未初始化。");
            return;
        }
        if (args.length == 0 || "status".equalsIgnoreCase(args[0])) {
            send(sender, "§aShitBot §7" + runtime.describeStatus());
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
            final UpdateChecker updateChecker = plugin.getUpdateChecker();
            if (updateChecker == null) {
                send(sender, "§c更新检查器尚未初始化。");
                return;
            }
            send(sender, "§e正在后台检查 GitHub Release...");
            updateChecker.checkAsync().whenComplete(
                    new java.util.function.BiConsumer<UpdateInfo, Throwable>() {
                        @Override
                        public void accept(final UpdateInfo info, final Throwable throwable) {
                            plugin.getPlatformBridge().executeOnPlatformThread(new Runnable() {
                                @Override
                                public void run() {
                                    if (throwable != null) {
                                        send(sender, "§c检查更新失败: §f" + errorMessage(throwable));
                                    } else if (updateChecker.isUpdateAvailable(info)) {
                                        plugin.sendUpdateNotice(sender, info);
                                    } else {
                                        send(sender, "§a当前已是最新版本: §f"
                                                + updateChecker.getCurrentVersion());
                                    }
                                }
                            });
                        }
                    });
            return;
        }
        if ("migrate".equalsIgnoreCase(args[0])) {
            if (args.length < 2 || !"easybot".equalsIgnoreCase(args[1])) {
                send(sender, "§e用法: /shitbot migrate easybot [EasyBot.db]");
                return;
            }
            final String fileName = args.length >= 3 ? args[2] : EasyBotMigrationService.DEFAULT_FILE_NAME;
            send(sender, "§e正在异步迁移 EasyBot 绑定数据: §f" + fileName);
            runtime.getEasyBotMigrationService().migrate(fileName).whenComplete(
                    new java.util.function.BiConsumer<EasyBotMigrationResult, Throwable>() {
                        @Override
                        public void accept(final EasyBotMigrationResult result, final Throwable throwable) {
                            plugin.getPlatformBridge().executeOnPlatformThread(new Runnable() {
                                @Override
                                public void run() {
                                    if (throwable != null) {
                                        send(sender, "§cEasyBot 迁移失败: §f" + errorMessage(throwable));
                                    } else {
                                        send(sender, "§aEasyBot 迁移完成: §f" + result.describe());
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
                                send(sender, "§c生成失败: " + FutureUtil.unwrap(throwable).getMessage());
                                return;
                            }
                            Path path = runtime.getImageService().getOutputPath();
                            send(sender, "§a在线图片已生成: §f" + path.toAbsolutePath());
                        }
                    });
            return;
        }
        send(sender, "§e/shitbot status|reload|update|image|migrate easybot [EasyBot.db]");
    }

    private String errorMessage(Throwable throwable) {
        Throwable cause = FutureUtil.unwrap(throwable);
        String message = cause.getMessage();
        return message == null || message.trim().isEmpty()
                ? cause.getClass().getSimpleName()
                : message;
    }

    private void send(CommandSender sender, String message) {
        sender.sendMessage(new TextComponent(message));
    }
}
