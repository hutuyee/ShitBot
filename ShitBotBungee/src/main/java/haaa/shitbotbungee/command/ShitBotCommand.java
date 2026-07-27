package haaa.shitbotbungee.command;

import haaa.shitbot.core.runtime.ShitBotRuntime;
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
        send(sender, "§e/shitbot status|reload|image");
    }

    private void send(CommandSender sender, String message) {
        sender.sendMessage(new TextComponent(message));
    }
}
