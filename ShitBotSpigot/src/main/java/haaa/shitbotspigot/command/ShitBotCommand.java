package haaa.shitbotspigot.command;

import haaa.shitbot.core.runtime.ShitBotRuntime;
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
                    plugin.getPlatformBridge().executeOnPlatformThread(new Runnable() {
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
        if ("image".equalsIgnoreCase(args[0])) {
            runtime.getImageService().renderOnlineImageAsync().whenComplete(
                    new java.util.function.BiConsumer<byte[], Throwable>() {
                        @Override
                        public void accept(byte[] bytes, final Throwable throwable) {
                            plugin.getPlatformBridge().executeOnPlatformThread(new Runnable() {
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
        sender.sendMessage("§e/shitbot status|reload|image");
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return Arrays.asList("status", "reload", "image");
        }
        return Collections.emptyList();
    }
}
