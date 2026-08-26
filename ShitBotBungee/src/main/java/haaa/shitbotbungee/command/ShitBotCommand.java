package haaa.shitbotbungee.command;

import haaa.shitbot.core.database.EasyBotMigrationResult;
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
            beginCoordinatedUpdate(sender);
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

    private void beginCoordinatedUpdate(final CommandSender sender) {
        final UpdateChecker updateChecker = plugin.getUpdateChecker();
        if (updateChecker == null) {
            send(sender, "§c更新检查器尚未初始化。");
            return;
        }
        send(sender, "§e正在后台检查 Release，并联动所有已配置后端...");
        updateChecker.checkAsync().whenComplete(
                new java.util.function.BiConsumer<UpdateInfo, Throwable>() {
                    @Override
                    public void accept(final UpdateInfo info, final Throwable throwable) {
                        if (throwable != null) {
                            runOnPlatform(new Runnable() {
                                @Override
                                public void run() {
                                    send(sender, "§c检查 Release 失败，未替换任何 JAR: §f"
                                            + errorMessage(throwable));
                                }
                            });
                            return;
                        }
                        installProxyAndBackends(sender, updateChecker, info);
                    }
                });
    }

    private void installProxyAndBackends(final CommandSender sender,
                                         UpdateChecker updateChecker,
                                         UpdateInfo info) {
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
                                    send(sender, "§c代理更新失败，现有 JAR 未替换: §f"
                                            + errorMessage(throwable));
                                } else {
                                    sendInstallResult(sender, result);
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
                                    send(sender, "§c后端联动失败: §f" + errorMessage(throwable));
                                    return;
                                }
                                if (results.isEmpty()) {
                                    send(sender, "§e未配置 backend-transport endpoint，未联动任何后端。");
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
                                send(sender, "§e联动处理完成；凡提示已替换的实例，请手动重启生效。");
                            }
                        });
                    }
                });
    }

    private void runOnPlatform(Runnable runnable) {
        plugin.getPlatformBridge().executeOnPlatformThread(runnable);
    }

    private void sendInstallResult(CommandSender sender, UpdateInstallResult result) {
        if (result.getStatus() == UpdateInstallResult.Status.UP_TO_DATE) {
            send(sender, "§a当前已是最新版本: §f" + result.getLatestVersion());
            return;
        }
        if (result.getStatus() == UpdateInstallResult.Status.ALREADY_INSTALLED) {
            send(sender, "§e新版本 §f" + result.getLatestVersion()
                    + " §e已经替换到磁盘，请手动重启代理生效。");
            return;
        }
        send(sender, "§a代理更新包已校验并替换: §f" + result.getLatestVersion());
        send(sender, "§7当前 JAR: §f" + result.getInstalledPath());
        send(sender, "§7备份 JAR: §f" + result.getBackupPath());
        send(sender, "§e请手动重启代理生效；不要执行插件热重载。");
    }

    private void send(CommandSender sender, String message) {
        sender.sendMessage(new TextComponent(message));
    }
}
