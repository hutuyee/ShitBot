package haaa.shitbot.core.config;

import java.util.Arrays;
import java.util.List;

public final class SettingsFactory {
    private SettingsFactory() {
    }

    public static Settings create(ConfigSource source,
                                  Translations translations,
                                  ImageTemplate onlineImageTemplate,
                                  ImageTemplate inventoryImageTemplate) {
        Settings.Command bindCommand = new Settings.Command(
                source.getBoolean("onebot.commands.bind.enabled", true),
                listOrDefault(translations.getList("commands.bind.aliases"), "bind", "/bind"),
                translations.get("commands.bind.usage"));
        Settings.Command onlineCommand = new Settings.Command(
                source.getBoolean("onebot.commands.online-image.enabled", true),
                listOrDefault(translations.getList("commands.online-image.aliases"), "server status", "online"),
                translations.get("commands.online-image.usage"));
        Settings.Command inventoryCommand = new Settings.Command(
                source.getBoolean("onebot.commands.inventory.enabled", true),
                listOrDefault(translations.getList("commands.inventory.aliases"), "inventory", "my inventory"),
                translations.get("commands.inventory.usage"));

        Settings.GroupJoinWelcome groupJoinWelcome = new Settings.GroupJoinWelcome(
                source.getBoolean("onebot.notices.group-join-welcome.enabled", true),
                translations.get("notices.group-join-welcome"));
        Settings.GroupLeaveUnbind groupLeaveUnbind = new Settings.GroupLeaveUnbind(
                source.getBoolean("onebot.notices.group-leave-unbind.enabled", false));
        Settings.ServerStartupNotice serverStartupNotice = new Settings.ServerStartupNotice(
                source.getBoolean("onebot.notices.server-startup.enabled", false),
                source.getString("onebot.notices.server-startup.target-server", ""),
                source.getInt("onebot.notices.server-startup.check-interval-seconds", 5),
                translations.get("notices.server-startup"));

        Settings.OneBot oneBot = new Settings.OneBot(
                source.getBoolean("onebot.enabled", true),
                source.getString("onebot.websocket-url", "ws://127.0.0.1:3001"),
                source.getString("onebot.access-token", ""),
                source.getBoolean("onebot.allow-insecure-remote-websocket", false),
                source.getLongList("onebot.allowed-group-ids"),
                source.getBoolean("onebot.allow-all-groups", false),
                source.getInt("onebot.connect-timeout-seconds", 10),
                source.getInt("onebot.action-timeout-seconds", 15),
                source.getInt("onebot.maximum-pending-actions", 256),
                source.getInt("onebot.heartbeat-timeout-seconds", 120),
                source.getInt("onebot.reconnect.initial-seconds", 3),
                source.getInt("onebot.reconnect.maximum-seconds", 60),
                source.getInt("onebot.command-cooldown-seconds", 2),
                source.getBoolean("onebot.reply-at-sender", true),
                bindCommand,
                onlineCommand,
                inventoryCommand,
                groupJoinWelcome,
                groupLeaveUnbind,
                serverStartupNotice);

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
                source.getInt("binding.maximum-attempts-per-qq",
                        source.getInt("binding.maximum-attempts", 5)),
                source.getInt("binding.maximum-total-attempts", 30),
                source.getInt("binding.total-attempt-cooldown-seconds", 30),
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
                        "useUnicode=true&characterEncoding=utf8&sslMode=DISABLED&serverTimezone=Asia/Tokyo&allowPublicKeyRetrieval=false"),
                source.getBoolean("database.mysql.allow-insecure-remote-mysql", false),
                source.getInt("database.mysql.connect-timeout-ms", 5000),
                source.getInt("database.mysql.socket-timeout-ms", 15000),
                source.getInt("database.pool.maximum-pool-size", 10),
                source.getInt("database.pool.minimum-idle", 2),
                source.getLong("database.pool.connection-timeout-ms", 5000L),
                source.getLong("database.pool.validation-timeout-ms", 3000L),
                source.getLong("database.pool.idle-timeout-ms", 600000L),
                source.getLong("database.pool.maximum-lifetime-ms", 1800000L),
                source.getLong("database.pool.keepalive-time-ms", 300000L),
                source.getInt("database.async-threads", 2),
                source.getInt("database.maximum-queued-tasks", 256));

        Settings.Image image = new Settings.Image(
                onlineImageTemplate,
                translations.get("image.title"),
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

        Settings.Inventory inventory = new Settings.Inventory(
                inventoryImageTemplate,
                source.getBoolean("inventory.enabled", true),
                translations.get("inventory.title"),
                source.getString("inventory.font-name", "Microsoft YaHei"),
                source.getInt("inventory.width", 760),
                source.getInt("inventory.slot-size", 48),
                source.getInt("inventory.snapshot.interval-seconds", 60),
                source.getInt("inventory.snapshot.retention-days", 30),
                source.getInt("inventory.cache.memory-maximum-entries", 2048),
                source.getInt("inventory.cache.render-seconds", 5),
                source.getInt("inventory.render.maximum-concurrent", 2),
                source.getInt("inventory.render.maximum-queued", 16),
                source.getString("inventory.output-file", "inventory.png"),
                source.getString("inventory.icons.exported-directory", "item-icons"),
                source.getBoolean("inventory.icons.scan-mod-jars", true),
                source.getString("inventory.icons.mods-directory", "../../mods"),
                source.getBoolean("inventory.icons.auto-discover", true),
                source.getInt("inventory.icons.refresh-seconds", 30),
                source.getInt("inventory.icons.index-wait-ms", 5000),
                source.getStringList("inventory.icons.resource-archives"),
                source.getInt("inventory.icons.cache-entries", 2048));

        Settings.Messages messages = new Settings.Messages(
                translations.get("messages.kick-unbound"),
                translations.get("messages.kick-after-unbind"),
                translations.get("messages.kick-database-unavailable"),
                translations.get("messages.bind-usage"),
                translations.get("messages.bind-success"),
                translations.get("messages.bind-invalid"),
                translations.get("messages.bind-expired"),
                translations.get("messages.bind-qq-already-used"),
                translations.get("messages.bind-qq-limit-reached"),
                translations.get("messages.bind-player-already-used"),
                translations.get("messages.bind-database-error"),
                translations.get("messages.online-failed"),
                translations.get("messages.inventory-not-bound"),
                translations.get("messages.inventory-player-not-bound"),
                translations.get("messages.inventory-unavailable"),
                translations.get("messages.inventory-disabled"),
                translations.get("messages.inventory-failed"),
                translations.get("messages.no-permission"),
                translations.get("messages.reload-started"),
                translations.get("messages.reload-success"),
                translations.get("messages.reload-failed"));

        return new Settings(source.getInt("config-version", 2), translations, oneBot, forwarding, binding,
                database, image, inventory, messages);
    }

    private static List<String> listOrDefault(List<String> values, String... fallback) {
        return values == null || values.isEmpty() ? Arrays.asList(fallback) : values;
    }
}
