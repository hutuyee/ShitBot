package haaa.shitbot.core.config;

import java.util.Arrays;
import java.util.List;

public final class SettingsFactory {
    private SettingsFactory() {
    }

    public static Settings create(ConfigSource source) {
        Settings.Command bindCommand = new Settings.Command(
                source.getBoolean("onebot.commands.bind.enabled", true),
                listOrDefault(source.getStringList("onebot.commands.bind.aliases"), "绑定", "/bind"),
                source.getString("onebot.commands.bind.usage", "用法: 绑定 <游戏ID> <验证码>"));
        Settings.Command onlineCommand = new Settings.Command(
                source.getBoolean("onebot.commands.online-image.enabled", true),
                listOrDefault(source.getStringList("onebot.commands.online-image.aliases"), "服务器状态", "在线人数"),
                source.getString("onebot.commands.online-image.usage", "服务器状态"));

        Settings.GroupJoinWelcome groupJoinWelcome = new Settings.GroupJoinWelcome(
                source.getBoolean("onebot.notices.group-join-welcome.enabled", true),
                source.getString("onebot.notices.group-join-welcome.message",
                        "欢迎 %at% 加入群聊！"));
        Settings.GroupLeaveUnbind groupLeaveUnbind = new Settings.GroupLeaveUnbind(
                source.getBoolean("onebot.notices.group-leave-unbind.enabled", false));

        Settings.OneBot oneBot = new Settings.OneBot(
                source.getBoolean("onebot.enabled", true),
                source.getString("onebot.websocket-url", "ws://127.0.0.1:3001"),
                source.getString("onebot.access-token", ""),
                source.getLongList("onebot.allowed-group-ids"),
                source.getInt("onebot.connect-timeout-seconds", 10),
                source.getInt("onebot.action-timeout-seconds", 15),
                source.getInt("onebot.heartbeat-timeout-seconds", 120),
                source.getInt("onebot.reconnect.initial-seconds", 3),
                source.getInt("onebot.reconnect.maximum-seconds", 60),
                source.getInt("onebot.command-cooldown-seconds", 2),
                source.getBoolean("onebot.reply-at-sender", true),
                bindCommand,
                onlineCommand,
                groupJoinWelcome,
                groupLeaveUnbind);

        Settings.Forwarding forwarding = new Settings.Forwarding(
                new Settings.Direction(
                        source.getBoolean("forwarding.game-to-group.enabled", false),
                        source.getBoolean("forwarding.game-to-group.require-prefix", true),
                        source.getString("forwarding.game-to-group.prefix", ""),
                        "#qq "),
                new Settings.Direction(
                        source.getBoolean("forwarding.group-to-game.enabled", false),
                        source.getBoolean("forwarding.group-to-game.require-prefix", true),
                        source.getString("forwarding.group-to-game.prefix", ""),
                        "#mc "),
                Settings.MediaMode.from(source.getString(
                        "forwarding.group-to-game.media-mode", "browser")));

        Settings.Binding binding = new Settings.Binding(
                source.getBoolean("binding.enabled", true),
                source.getInt("binding.code-length", 6),
                source.getInt("binding.expire-minutes", 10),
                source.getInt("binding.maximum-attempts", 5),
                source.getInt("binding.login-database-timeout-seconds", 8),
                source.getBoolean("binding.allow-multiple-ids-per-qq", true),
                source.getInt("binding.maximum-ids-per-qq", 5),
                source.getString("binding.code-alphabet", "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"));

        Settings.Database database = new Settings.Database(
                Settings.Database.Type.from(source.getString("database.type", "sqlite")),
                source.getString("database.sqlite.file", "shitbot.db"),
                source.getString("database.mysql.host", "127.0.0.1"),
                source.getInt("database.mysql.port", 3306),
                source.getString("database.mysql.database", "shitbot"),
                source.getString("database.mysql.username", "shitbot"),
                source.getString("database.mysql.password", "change_me"),
                source.getString("database.mysql.parameters",
                        "useUnicode=true&characterEncoding=utf8&useSSL=false&serverTimezone=Asia/Tokyo&allowPublicKeyRetrieval=true"),
                source.getInt("database.pool.maximum-pool-size", 10),
                source.getInt("database.pool.minimum-idle", 2),
                source.getLong("database.pool.connection-timeout-ms", 5000L),
                source.getLong("database.pool.validation-timeout-ms", 3000L),
                source.getLong("database.pool.idle-timeout-ms", 600000L),
                source.getLong("database.pool.maximum-lifetime-ms", 80000L),
                source.getLong("database.pool.keepalive-time-ms", 40000L),
                source.getInt("database.async-threads", 2));

        Settings.Image image = new Settings.Image(
                source.getString("image.title", "服务器在线状态"),
                source.getString("image.server-name", "Minecraft Server"),
                source.getString("image.font-name", "Microsoft YaHei"),
                source.getInt("image.width", 1200),
                source.getInt("image.players-per-row", 5),
                source.getInt("image.maximum-players", 200),
                source.getInt("image.cache-seconds", 8),
                source.getString("image.output-file", "online.png"),
                source.getBoolean("image.avatar.enabled", true),
                source.getString("image.avatar.url-template", "https://mc-heads.net/avatar/%player%/64"),
                source.getInt("image.avatar.size", 36),
                source.getInt("image.avatar.cache-minutes", 1440),
                source.getInt("image.avatar.download-threads", 4),
                source.getInt("image.avatar.maximum-downloads-per-render", 24),
                source.getInt("image.avatar.connect-timeout-ms", 1500),
                source.getInt("image.avatar.read-timeout-ms", 2500),
                source.getInt("image.avatar.wait-timeout-ms", 2200));

        Settings.Messages messages = new Settings.Messages(
                source.getString("messages.kick-unbound",
                        "&7欢迎 &a%player% &7加入服务器\n&7请在QQ群发送 &6绑定 %player% %code%\n&7验证码将在 &c%expire_minutes% 分钟 &7后失效"),
                source.getString("messages.kick-database-unavailable", "&c绑定系统暂时不可用，请稍后重试。"),
                source.getString("messages.bind-usage", "用法: 绑定 <游戏ID> <验证码>"),
                source.getString("messages.bind-success", "%at% 绑定成功：%player% -> %qq%"),
                source.getString("messages.bind-invalid", "%at% 验证码错误。"),
                source.getString("messages.bind-expired", "%at% 验证码不存在或已经过期，请重新进入服务器获取。"),
                source.getString("messages.bind-qq-already-used", "%at% 该QQ已经绑定其他游戏ID。"),
                source.getString("messages.bind-qq-limit-reached",
                        "%at% 该QQ绑定的游戏ID数量已达到上限（%maximum_ids%个）。"),
                source.getString("messages.bind-player-already-used", "%at% 该游戏ID已经绑定其他QQ。"),
                source.getString("messages.bind-database-error", "%at% 数据库操作失败，请联系管理员。"),
                source.getString("messages.online-failed", "%at% 在线人数图片生成失败，请稍后重试。"),
                source.getString("messages.no-permission", "&c你没有权限使用此命令。"),
                source.getString("messages.reload-started", "&7正在重载 ShitBot..."),
                source.getString("messages.reload-success", "&aShitBot 已完成热重载。"),
                source.getString("messages.reload-failed", "&cShitBot 重载失败，旧配置仍在运行。"));

        return new Settings(source.getInt("config-version", 1), oneBot, forwarding, binding, database, image, messages);
    }

    private static List<String> listOrDefault(List<String> values, String... fallback) {
        return values == null || values.isEmpty() ? Arrays.asList(fallback) : values;
    }
}
