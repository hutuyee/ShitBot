package haaa.shitbotspigot.command;

import haaa.shitbot.core.database.EasyBotMigrationResult;
import haaa.shitbot.core.runtime.ShitBotRuntime;
import haaa.shitbot.core.service.EasyBotMigrationService;
import haaa.shitbot.core.update.UpdateChecker;
import haaa.shitbot.core.update.UpdateInfo;
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
        if (runtime == null) {
            sender.sendMessage("§cShitBot 尚未初始化。");
            return true;
        }
        if (args.length == 0 || "status".equalsIgnoreCase(args[0])) {
            sender.sendMessage("§aShitBot §7" + runtime.describeStatus());
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
                sender.sendMessage("§c更新检查器尚未初始化。");
                return true;
            }
            sender.sendMessage("§e正在后台检查 GitHub Release...");
            updateChecker.checkAsync().whenComplete(
                    new java.util.function.BiConsumer<UpdateInfo, Throwable>() {
                        @Override
                        public void accept(final UpdateInfo info, final Throwable throwable) {
                            plugin.getPlatformBridge().executeOnSenderThread(sender, new Runnable() {
                                @Override
                                public void run() {
                                    if (throwable != null) {
                                        sender.sendMessage("§c检查更新失败: §f" + errorMessage(throwable));
                                    } else if (updateChecker.isUpdateAvailable(info)) {
                                        plugin.sendUpdateNotice(sender, info);
                                    } else {
                                        sender.sendMessage("§a当前已是最新版本: §f"
                                                + updateChecker.getCurrentVersion());
                                    }
                                }
                            });
                        }
                    });
            return true;
        }
        if ("migrate".equalsIgnoreCase(args[0])) {
            if (args.length < 2 || !"easybot".equalsIgnoreCase(args[1])) {
                sender.sendMessage("§e用法: /shitbot migrate easybot [EasyBot.db]");
                return true;
            }
            final String fileName = args.length >= 3 ? args[2] : EasyBotMigrationService.DEFAULT_FILE_NAME;
            sender.sendMessage("§e正在异步迁移 EasyBot 绑定数据: §f" + fileName);
            runtime.getEasyBotMigrationService().migrate(fileName).whenComplete(
                    new java.util.function.BiConsumer<EasyBotMigrationResult, Throwable>() {
                        @Override
                        public void accept(final EasyBotMigrationResult result, final Throwable throwable) {
                            plugin.getPlatformBridge().executeOnSenderThread(sender, new Runnable() {
                                @Override
                                public void run() {
                                    if (throwable != null) {
                                        sender.sendMessage("§cEasyBot 迁移失败: §f" + errorMessage(throwable));
                                    } else {
                                        sender.sendMessage("§aEasyBot 迁移完成: §f" + result.describe());
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
                                        sender.sendMessage("§c生成失败: " + FutureUtil.unwrap(throwable).getMessage());
                                    } else {
                                        Path path = runtime.getImageService().getOutputPath();
                                        sender.sendMessage("§a在线图片已生成: §f" + path.toAbsolutePath());
                                    }
                                }
                            });
                        }
                    });
            return true;
        }
        sender.sendMessage("§e/shitbot status|reload|update|image|migrate easybot [EasyBot.db]");
        return true;
    }

    private String errorMessage(Throwable throwable) {
        Throwable cause = FutureUtil.unwrap(throwable);
        String message = cause.getMessage();
        return message == null || message.trim().isEmpty()
                ? cause.getClass().getSimpleName()
                : message;
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
