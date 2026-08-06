package haaa.shitbot.core.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Immutable runtime configuration shared by every platform build.
 * Platform-specific loaders translate config.yml into this object.
 */
public final class Settings {

    private final int configVersion;
    private final OneBot oneBot;
    private final Forwarding forwarding;
    private final Binding binding;
    private final Database database;
    private final Image image;
    private final Inventory inventory;
    private final Messages messages;

    public Settings(int configVersion,
                    OneBot oneBot,
                    Forwarding forwarding,
                    Binding binding,
                    Database database,
                    Image image,
                    Inventory inventory,
                    Messages messages) {
        this.configVersion = configVersion;
        this.oneBot = require(oneBot, "oneBot");
        this.forwarding = require(forwarding, "forwarding");
        this.binding = require(binding, "binding");
        this.database = require(database, "database");
        this.image = require(image, "image");
        this.inventory = require(inventory, "inventory");
        this.messages = require(messages, "messages");
    }

    public int getConfigVersion() {
        return configVersion;
    }

    public OneBot getOneBot() {
        return oneBot;
    }

    public Forwarding getForwarding() {
        return forwarding;
    }

    public Binding getBinding() {
        return binding;
    }

    public Database getDatabase() {
        return database;
    }

    public Image getImage() {
        return image;
    }

    public Inventory getInventory() {
        return inventory;
    }

    public Messages getMessages() {
        return messages;
    }

    private static <T> T require(T value, String name) {
        if (value == null) {
            throw new IllegalArgumentException(name + " cannot be null");
        }
        return value;
    }

    private static String text(String value, String fallback) {
        if (value == null) {
            return fallback;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? fallback : trimmed;
    }

    private static List<String> immutableStrings(List<String> values) {
        if (values == null || values.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> result = new ArrayList<String>(values.size());
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                result.add(value.trim());
            }
        }
        return Collections.unmodifiableList(result);
    }

    public static final class OneBot {
        private final boolean enabled;
        private final String websocketUrl;
        private final String accessToken;
        private final List<Long> allowedGroupIds;
        private final int connectTimeoutSeconds;
        private final int actionTimeoutSeconds;
        private final int heartbeatTimeoutSeconds;
        private final int reconnectInitialSeconds;
        private final int reconnectMaximumSeconds;
        private final int commandCooldownSeconds;
        private final boolean replyAtSender;
        private final Command bindCommand;
        private final Command onlineImageCommand;
        private final Command inventoryCommand;
        private final GroupJoinWelcome groupJoinWelcome;
        private final GroupLeaveUnbind groupLeaveUnbind;

        public OneBot(boolean enabled,
                      String websocketUrl,
                      String accessToken,
                      List<Long> allowedGroupIds,
                      int connectTimeoutSeconds,
                      int actionTimeoutSeconds,
                      int heartbeatTimeoutSeconds,
                      int reconnectInitialSeconds,
                      int reconnectMaximumSeconds,
                      int commandCooldownSeconds,
                      boolean replyAtSender,
                      Command bindCommand,
                      Command onlineImageCommand,
                      Command inventoryCommand,
                      GroupJoinWelcome groupJoinWelcome,
                      GroupLeaveUnbind groupLeaveUnbind) {
            this.enabled = enabled;
            this.websocketUrl = text(websocketUrl, "ws://127.0.0.1:3001");
            this.accessToken = accessToken == null ? "" : accessToken.trim();
            this.allowedGroupIds = allowedGroupIds == null
                    ? Collections.<Long>emptyList()
                    : Collections.unmodifiableList(new ArrayList<Long>(allowedGroupIds));
            this.connectTimeoutSeconds = clamp(connectTimeoutSeconds, 1, 60, 10);
            this.actionTimeoutSeconds = clamp(actionTimeoutSeconds, 1, 120, 15);
            this.heartbeatTimeoutSeconds = clamp(heartbeatTimeoutSeconds, 15, 600, 120);
            this.reconnectInitialSeconds = clamp(reconnectInitialSeconds, 1, 60, 3);
            this.reconnectMaximumSeconds = clamp(reconnectMaximumSeconds, this.reconnectInitialSeconds, 600, 60);
            this.commandCooldownSeconds = clamp(commandCooldownSeconds, 0, 300, 2);
            this.replyAtSender = replyAtSender;
            this.bindCommand = require(bindCommand, "bindCommand");
            this.onlineImageCommand = require(onlineImageCommand, "onlineImageCommand");
            this.inventoryCommand = require(inventoryCommand, "inventoryCommand");
            this.groupJoinWelcome = require(groupJoinWelcome, "groupJoinWelcome");
            this.groupLeaveUnbind = require(groupLeaveUnbind, "groupLeaveUnbind");
        }

        public boolean isEnabled() {
            return enabled;
        }

        public String getWebsocketUrl() {
            return websocketUrl;
        }

        public String getAccessToken() {
            return accessToken;
        }

        public List<Long> getAllowedGroupIds() {
            return allowedGroupIds;
        }

        public int getConnectTimeoutSeconds() {
            return connectTimeoutSeconds;
        }

        public int getActionTimeoutSeconds() {
            return actionTimeoutSeconds;
        }

        public int getHeartbeatTimeoutSeconds() {
            return heartbeatTimeoutSeconds;
        }

        public int getReconnectInitialSeconds() {
            return reconnectInitialSeconds;
        }

        public int getReconnectMaximumSeconds() {
            return reconnectMaximumSeconds;
        }

        public int getCommandCooldownSeconds() {
            return commandCooldownSeconds;
        }

        public boolean isReplyAtSender() {
            return replyAtSender;
        }

        public Command getBindCommand() {
            return bindCommand;
        }

        public Command getOnlineImageCommand() {
            return onlineImageCommand;
        }

        public Command getInventoryCommand() {
            return inventoryCommand;
        }

        public GroupJoinWelcome getGroupJoinWelcome() {
            return groupJoinWelcome;
        }

        public GroupLeaveUnbind getGroupLeaveUnbind() {
            return groupLeaveUnbind;
        }

        public boolean isGroupAllowed(long groupId) {
            return allowedGroupIds.isEmpty() || allowedGroupIds.contains(Long.valueOf(groupId));
        }
    }

    public static final class Command {
        private final boolean enabled;
        private final List<String> aliases;
        private final String usage;

        public Command(boolean enabled, List<String> aliases, String usage) {
            this.enabled = enabled;
            this.aliases = immutableStrings(aliases);
            this.usage = text(usage, "");
        }

        public boolean isEnabled() {
            return enabled;
        }

        public List<String> getAliases() {
            return aliases;
        }

        public String getUsage() {
            return usage;
        }
    }

    public static final class GroupJoinWelcome {
        private final boolean enabled;
        private final String message;

        public GroupJoinWelcome(boolean enabled, String message) {
            this.enabled = enabled;
            this.message = text(message, "欢迎 %at% 加入群聊！");
        }

        public boolean isEnabled() {
            return enabled;
        }

        public String getMessage() {
            return message;
        }
    }

    public static final class GroupLeaveUnbind {
        private final boolean enabled;

        public GroupLeaveUnbind(boolean enabled) {
            this.enabled = enabled;
        }

        public boolean isEnabled() {
            return enabled;
        }
    }

    public static final class Forwarding {
        private final Direction gameToGroup;
        private final Direction groupToGame;
        private final MediaMode groupToGameMediaMode;

        public Forwarding(Direction gameToGroup, Direction groupToGame) {
            this(gameToGroup, groupToGame, MediaMode.BROWSER);
        }

        public Forwarding(Direction gameToGroup, Direction groupToGame, MediaMode groupToGameMediaMode) {
            this.gameToGroup = require(gameToGroup, "gameToGroup");
            this.groupToGame = require(groupToGame, "groupToGame");
            this.groupToGameMediaMode = require(groupToGameMediaMode, "groupToGameMediaMode");
        }

        public Direction getGameToGroup() {
            return gameToGroup;
        }

        public Direction getGroupToGame() {
            return groupToGame;
        }

        public MediaMode getGroupToGameMediaMode() {
            return groupToGameMediaMode;
        }
    }

    public enum MediaMode {
        BROWSER,
        PICTUREBRIDGE;

        public static MediaMode from(String value) {
            if (value != null && "picturebridge".equalsIgnoreCase(value.trim())) {
                return PICTUREBRIDGE;
            }
            return BROWSER;
        }
    }

    public static final class Direction {
        private final boolean enabled;
        private final boolean requirePrefix;
        private final String prefix;

        public Direction(boolean enabled, boolean requirePrefix, String prefix, String fallbackPrefix) {
            this.enabled = enabled;
            this.prefix = prefix == null ? (fallbackPrefix == null ? "" : fallbackPrefix) : prefix;
            // An empty prefix means there is no trigger prefix, so all non-empty messages pass.
            this.requirePrefix = requirePrefix && !this.prefix.isEmpty();
        }

        public boolean isEnabled() {
            return enabled;
        }

        public boolean isRequirePrefix() {
            return requirePrefix;
        }

        public String getPrefix() {
            return prefix;
        }

        public String extractContent(String message) {
            if (!enabled || message == null) {
                return null;
            }
            String content = message;
            if (requirePrefix) {
                if (!content.startsWith(prefix)) {
                    return null;
                }
                content = content.substring(prefix.length());
            }
            content = content.trim();
            return content.isEmpty() ? null : content;
        }
    }

    public static final class Binding {
        private final boolean enabled;
        private final int codeLength;
        private final int expireMinutes;
        private final int maximumAttempts;
        private final int loginDatabaseTimeoutSeconds;
        private final boolean allowMultipleIdsPerQq;
        private final int maximumIdsPerQq;
        private final String codeAlphabet;

        public Binding(boolean enabled,
                       int codeLength,
                       int expireMinutes,
                       int maximumAttempts,
                       int loginDatabaseTimeoutSeconds,
                       boolean allowMultipleIdsPerQq,
                       int maximumIdsPerQq,
                       String codeAlphabet) {
            this.enabled = enabled;
            this.codeLength = clamp(codeLength, 4, 12, 6);
            this.expireMinutes = clamp(expireMinutes, 1, 1440, 10);
            this.maximumAttempts = clamp(maximumAttempts, 1, 20, 5);
            this.loginDatabaseTimeoutSeconds = clamp(loginDatabaseTimeoutSeconds, 1, 60, 8);
            this.allowMultipleIdsPerQq = allowMultipleIdsPerQq;
            this.maximumIdsPerQq = clamp(maximumIdsPerQq, 1, 1000, 5);
            String normalizedAlphabet = text(codeAlphabet, "ABCDEFGHJKLMNPQRSTUVWXYZ23456789").toUpperCase(Locale.ROOT);
            this.codeAlphabet = normalizedAlphabet.length() < 8
                    ? "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
                    : normalizedAlphabet;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public int getCodeLength() {
            return codeLength;
        }

        public int getExpireMinutes() {
            return expireMinutes;
        }

        public int getMaximumAttempts() {
            return maximumAttempts;
        }

        public int getLoginDatabaseTimeoutSeconds() {
            return loginDatabaseTimeoutSeconds;
        }

        /** Whether one QQ number may own more than one exact Minecraft player name. */
        public boolean isAllowMultipleIdsPerQq() {
            return allowMultipleIdsPerQq;
        }

        /**
         * Effective per-QQ binding limit. Disabling multi-ID binding always
         * forces the limit to one, regardless of maximum-ids-per-qq.
         */
        public int getMaximumIdsPerQq() {
            return allowMultipleIdsPerQq ? maximumIdsPerQq : 1;
        }

        public String getCodeAlphabet() {
            return codeAlphabet;
        }
    }

    public static final class Database {
        public enum Type {
            SQLITE,
            MYSQL;

            public static Type from(String value) {
                if (value != null && "mysql".equalsIgnoreCase(value.trim())) {
                    return MYSQL;
                }
                return SQLITE;
            }
        }

        private final Type type;
        private final String sqliteFile;
        private final String mysqlHost;
        private final int mysqlPort;
        private final String mysqlDatabase;
        private final String mysqlUsername;
        private final String mysqlPassword;
        private final String mysqlParameters;
        private final int maximumPoolSize;
        private final int minimumIdle;
        private final long connectionTimeoutMs;
        private final long validationTimeoutMs;
        private final long idleTimeoutMs;
        private final long maximumLifetimeMs;
        private final long keepaliveTimeMs;
        private final int asyncThreads;

        public Database(Type type,
                        String sqliteFile,
                        String mysqlHost,
                        int mysqlPort,
                        String mysqlDatabase,
                        String mysqlUsername,
                        String mysqlPassword,
                        String mysqlParameters,
                        int maximumPoolSize,
                        int minimumIdle,
                        long connectionTimeoutMs,
                        long validationTimeoutMs,
                        long idleTimeoutMs,
                        long maximumLifetimeMs,
                        long keepaliveTimeMs,
                        int asyncThreads) {
            this.type = type == null ? Type.SQLITE : type;
            this.sqliteFile = sanitizeFileName(sqliteFile, "shitbot.db");
            this.mysqlHost = text(mysqlHost, "127.0.0.1");
            this.mysqlPort = clamp(mysqlPort, 1, 65535, 3306);
            this.mysqlDatabase = text(mysqlDatabase, "shitbot");
            this.mysqlUsername = text(mysqlUsername, "shitbot");
            this.mysqlPassword = mysqlPassword == null ? "" : mysqlPassword;
            this.mysqlParameters = text(mysqlParameters,
                    "useUnicode=true&characterEncoding=utf8&useSSL=false&serverTimezone=Asia/Tokyo&allowPublicKeyRetrieval=true");
            this.maximumPoolSize = clamp(maximumPoolSize, 1, 50, 10);
            this.minimumIdle = clamp(minimumIdle, 0, this.maximumPoolSize, 2);
            this.connectionTimeoutMs = clampLong(connectionTimeoutMs, 1000L, 120000L, 5000L);
            this.validationTimeoutMs = clampLong(validationTimeoutMs, 500L, this.connectionTimeoutMs, 3000L);
            this.idleTimeoutMs = clampLong(idleTimeoutMs, 10000L, 1800000L, 600000L);
            this.maximumLifetimeMs = clampLong(maximumLifetimeMs, 30000L, 3600000L, 80000L);
            this.keepaliveTimeMs = clampLong(keepaliveTimeMs, 0L, this.maximumLifetimeMs - 1000L, 40000L);
            this.asyncThreads = clamp(asyncThreads, 1, 16, 2);
        }

        public Type getType() {
            return type;
        }

        public String getSqliteFile() {
            return sqliteFile;
        }

        public String getMysqlHost() {
            return mysqlHost;
        }

        public int getMysqlPort() {
            return mysqlPort;
        }

        public String getMysqlDatabase() {
            return mysqlDatabase;
        }

        public String getMysqlUsername() {
            return mysqlUsername;
        }

        public String getMysqlPassword() {
            return mysqlPassword;
        }

        public String getMysqlParameters() {
            return mysqlParameters;
        }

        public int getMaximumPoolSize() {
            return type == Type.SQLITE ? 1 : maximumPoolSize;
        }

        public int getMinimumIdle() {
            return type == Type.SQLITE ? 1 : minimumIdle;
        }

        public long getConnectionTimeoutMs() {
            return connectionTimeoutMs;
        }

        public long getValidationTimeoutMs() {
            return validationTimeoutMs;
        }

        public long getIdleTimeoutMs() {
            return idleTimeoutMs;
        }

        public long getMaximumLifetimeMs() {
            return maximumLifetimeMs;
        }

        public long getKeepaliveTimeMs() {
            return type == Type.SQLITE ? 0L : keepaliveTimeMs;
        }

        public int getAsyncThreads() {
            return type == Type.SQLITE ? 1 : asyncThreads;
        }

        public String buildJdbcUrl(java.nio.file.Path dataDirectory) {
            if (type == Type.SQLITE) {
                return "jdbc:sqlite:" + dataDirectory.resolve(sqliteFile).toAbsolutePath().normalize().toString();
            }
            String suffix = mysqlParameters.startsWith("?") ? mysqlParameters : "?" + mysqlParameters;
            return "jdbc:mysql://" + mysqlHost + ':' + mysqlPort + '/' + mysqlDatabase + suffix;
        }

        private static String sanitizeFileName(String value, String fallback) {
            String candidate = text(value, fallback).replace('\\', '/');
            int slash = candidate.lastIndexOf('/');
            if (slash >= 0) {
                candidate = candidate.substring(slash + 1);
            }
            if (candidate.isEmpty() || ".".equals(candidate) || "..".equals(candidate)) {
                return fallback;
            }
            return candidate;
        }
    }

    public static final class Image {
        private final String title;
        private final String serverName;
        private final String fontName;
        private final int width;
        private final int playersPerRow;
        private final int maximumPlayers;
        private final int cacheSeconds;
        private final String outputFile;
        private final boolean avatarEnabled;
        private final String avatarUrlTemplate;
        private final int avatarSize;
        private final int avatarCacheMinutes;
        private final int avatarDownloadThreads;
        private final int avatarMaximumDownloadsPerRender;
        private final int avatarConnectTimeoutMs;
        private final int avatarReadTimeoutMs;
        private final int avatarWaitTimeoutMs;

        public Image(String title,
                     String serverName,
                     String fontName,
                     int width,
                     int playersPerRow,
                     int maximumPlayers,
                     int cacheSeconds,
                     String outputFile,
                     boolean avatarEnabled,
                     String avatarUrlTemplate,
                     int avatarSize,
                     int avatarCacheMinutes,
                     int avatarDownloadThreads,
                     int avatarMaximumDownloadsPerRender,
                     int avatarConnectTimeoutMs,
                     int avatarReadTimeoutMs,
                     int avatarWaitTimeoutMs) {
            this.title = text(title, "服务器在线状态");
            this.serverName = text(serverName, "Minecraft Server");
            this.fontName = text(fontName, "Microsoft YaHei");
            this.width = clamp(width, 720, 2400, 1200);
            this.playersPerRow = clamp(playersPerRow, 1, 12, 5);
            this.maximumPlayers = clamp(maximumPlayers, 1, 1000, 200);
            this.cacheSeconds = clamp(cacheSeconds, 0, 300, 8);
            this.outputFile = Database.sanitizeFileName(outputFile, "online.png");
            this.avatarEnabled = avatarEnabled;
            this.avatarUrlTemplate = text(avatarUrlTemplate, "https://mc-heads.net/avatar/%player%/64");
            this.avatarSize = clamp(avatarSize, 24, 64, 36);
            this.avatarCacheMinutes = clamp(avatarCacheMinutes, 1, 10080, 1440);
            this.avatarDownloadThreads = clamp(avatarDownloadThreads, 1, 12, 4);
            this.avatarMaximumDownloadsPerRender = clamp(avatarMaximumDownloadsPerRender, 1, 200, 24);
            this.avatarConnectTimeoutMs = clamp(avatarConnectTimeoutMs, 250, 10000, 1500);
            this.avatarReadTimeoutMs = clamp(avatarReadTimeoutMs, 250, 15000, 2500);
            this.avatarWaitTimeoutMs = clamp(avatarWaitTimeoutMs, 0, 10000, 2200);
        }

        public String getTitle() {
            return title;
        }

        public String getServerName() {
            return serverName;
        }

        public String getFontName() {
            return fontName;
        }

        public int getWidth() {
            return width;
        }

        public int getPlayersPerRow() {
            return playersPerRow;
        }

        public int getMaximumPlayers() {
            return maximumPlayers;
        }

        public int getCacheSeconds() {
            return cacheSeconds;
        }

        public String getOutputFile() {
            return outputFile;
        }

        public boolean isAvatarEnabled() {
            return avatarEnabled;
        }

        public String getAvatarUrlTemplate() {
            return avatarUrlTemplate;
        }

        public int getAvatarSize() {
            return avatarSize;
        }

        public int getAvatarCacheMinutes() {
            return avatarCacheMinutes;
        }

        public int getAvatarDownloadThreads() {
            return avatarDownloadThreads;
        }

        public int getAvatarMaximumDownloadsPerRender() {
            return avatarMaximumDownloadsPerRender;
        }

        public int getAvatarConnectTimeoutMs() {
            return avatarConnectTimeoutMs;
        }

        public int getAvatarReadTimeoutMs() {
            return avatarReadTimeoutMs;
        }

        public int getAvatarWaitTimeoutMs() {
            return avatarWaitTimeoutMs;
        }
    }

    public static final class Inventory {
        private final boolean enabled;
        private final String title;
        private final String fontName;
        private final int width;
        private final int slotSize;
        private final int snapshotIntervalSeconds;
        private final int snapshotRetentionDays;
        private final int memoryMaximumEntries;
        private final int renderCacheSeconds;
        private final int maximumConcurrentRenders;
        private final String outputFile;
        private final String exportedIconsDirectory;
        private final boolean scanModJars;
        private final String modsDirectory;
        private final boolean autoDiscoverResources;
        private final int resourceRefreshSeconds;
        private final int resourceIndexWaitMs;
        private final List<String> resourceArchives;
        private final int iconCacheEntries;

        public Inventory(boolean enabled,
                         String title,
                         String fontName,
                         int width,
                         int slotSize,
                         int snapshotIntervalSeconds,
                         int snapshotRetentionDays,
                         int memoryMaximumEntries,
                         int renderCacheSeconds,
                         int maximumConcurrentRenders,
                         String outputFile,
                         String exportedIconsDirectory,
                         boolean scanModJars,
                         String modsDirectory,
                         boolean autoDiscoverResources,
                         int resourceRefreshSeconds,
                         int resourceIndexWaitMs,
                         List<String> resourceArchives,
                         int iconCacheEntries) {
            this.enabled = enabled;
            this.title = text(title, "%player% 的背包");
            this.fontName = text(fontName, "Microsoft YaHei");
            this.width = clamp(width, 560, 2400, 760);
            this.slotSize = clamp(slotSize, 32, 96, 48);
            this.snapshotIntervalSeconds = clamp(snapshotIntervalSeconds, 15, 3600, 60);
            this.snapshotRetentionDays = clamp(snapshotRetentionDays, 1, 3650, 30);
            this.memoryMaximumEntries = clamp(memoryMaximumEntries, 64, 100000, 2048);
            this.renderCacheSeconds = Math.max(0, Math.min(300, renderCacheSeconds));
            this.maximumConcurrentRenders = clamp(maximumConcurrentRenders, 1, 8, 2);
            this.outputFile = Database.sanitizeFileName(outputFile, "inventory.png");
            this.exportedIconsDirectory = text(exportedIconsDirectory, "item-icons");
            this.scanModJars = scanModJars;
            this.modsDirectory = text(modsDirectory, "../../mods");
            this.autoDiscoverResources = autoDiscoverResources;
            this.resourceRefreshSeconds = clamp(resourceRefreshSeconds, 5, 3600, 30);
            this.resourceIndexWaitMs = clamp(resourceIndexWaitMs, 0, 30000, 5000);
            this.resourceArchives = immutableStrings(resourceArchives);
            this.iconCacheEntries = clamp(iconCacheEntries, 64, 10000, 2048);
        }

        public boolean isEnabled() { return enabled; }
        public String getTitle() { return title; }
        public String getFontName() { return fontName; }
        public int getWidth() { return width; }
        public int getSlotSize() { return slotSize; }
        public int getSnapshotIntervalSeconds() { return snapshotIntervalSeconds; }
        public int getSnapshotRetentionDays() { return snapshotRetentionDays; }
        public int getMemoryMaximumEntries() { return memoryMaximumEntries; }
        public int getRenderCacheSeconds() { return renderCacheSeconds; }
        public int getMaximumConcurrentRenders() { return maximumConcurrentRenders; }
        public String getOutputFile() { return outputFile; }
        public String getExportedIconsDirectory() { return exportedIconsDirectory; }
        public boolean isScanModJars() { return scanModJars; }
        public String getModsDirectory() { return modsDirectory; }
        public boolean isAutoDiscoverResources() { return autoDiscoverResources; }
        public int getResourceRefreshSeconds() { return resourceRefreshSeconds; }
        public int getResourceIndexWaitMs() { return resourceIndexWaitMs; }
        public List<String> getResourceArchives() { return resourceArchives; }
        public int getIconCacheEntries() { return iconCacheEntries; }
    }

    public static final class Messages {
        private final String kickUnbound;
        private final String kickAfterUnbind;
        private final String kickDatabaseUnavailable;
        private final String bindUsage;
        private final String bindSuccess;
        private final String bindInvalid;
        private final String bindExpired;
        private final String bindQqAlreadyUsed;
        private final String bindQqLimitReached;
        private final String bindPlayerAlreadyUsed;
        private final String bindDatabaseError;
        private final String onlineFailed;
        private final String inventoryNotBound;
        private final String inventoryPlayerNotBound;
        private final String inventoryUnavailable;
        private final String inventoryDisabled;
        private final String inventoryFailed;
        private final String noPermission;
        private final String reloadStarted;
        private final String reloadSuccess;
        private final String reloadFailed;

        public Messages(String kickUnbound,
                        String kickAfterUnbind,
                        String kickDatabaseUnavailable,
                        String bindUsage,
                        String bindSuccess,
                        String bindInvalid,
                        String bindExpired,
                        String bindQqAlreadyUsed,
                        String bindQqLimitReached,
                        String bindPlayerAlreadyUsed,
                        String bindDatabaseError,
                        String onlineFailed,
                        String inventoryNotBound,
                        String inventoryPlayerNotBound,
                        String inventoryUnavailable,
                        String inventoryDisabled,
                        String inventoryFailed,
                        String noPermission,
                        String reloadStarted,
                        String reloadSuccess,
                        String reloadFailed) {
            this.kickUnbound = text(kickUnbound,
                    "&7欢迎 &a%player% &7加入服务器\n&7请在QQ群发送 &6绑定 %player% %code%\n&7验证码将在 &c%expire_minutes% 分钟 &7后失效");
            this.kickAfterUnbind = text(kickAfterUnbind,
                    "&c你的账号绑定已解除，请重新进入服务器获取验证码并完成绑定。");
            this.kickDatabaseUnavailable = text(kickDatabaseUnavailable, "&c绑定系统暂时不可用，请稍后重试。");
            this.bindUsage = text(bindUsage, "用法: 绑定 <游戏ID> <验证码>");
            this.bindSuccess = text(bindSuccess, "%at% 绑定成功：%player% -> %qq%");
            this.bindInvalid = text(bindInvalid, "%at% 验证码错误。");
            this.bindExpired = text(bindExpired, "%at% 验证码不存在或已经过期，请重新进入服务器获取。");
            this.bindQqAlreadyUsed = text(bindQqAlreadyUsed, "%at% 该QQ已经绑定其他游戏ID。");
            this.bindQqLimitReached = text(bindQqLimitReached,
                    "%at% 该QQ绑定的游戏ID数量已达到上限（%maximum_ids%个）。");
            this.bindPlayerAlreadyUsed = text(bindPlayerAlreadyUsed, "%at% 该游戏ID已经绑定其他QQ。");
            this.bindDatabaseError = text(bindDatabaseError, "%at% 数据库操作失败，请联系管理员。");
            this.onlineFailed = text(onlineFailed, "%at% 在线人数图片生成失败，请稍后重试。");
            this.inventoryNotBound = text(inventoryNotBound, "%at% 你还没有绑定游戏ID。");
            this.inventoryPlayerNotBound = text(inventoryPlayerNotBound,
                    "%at% 游戏ID %player% 未绑定到你的QQ，无法查询。");
            this.inventoryUnavailable = text(inventoryUnavailable, "%at% 暂无你的背包快照，请先进入一次服务器。");
            this.inventoryDisabled = text(inventoryDisabled, "%at% 背包查询当前未启用。");
            this.inventoryFailed = text(inventoryFailed, "%at% 背包查询失败，请稍后重试。");
            this.noPermission = text(noPermission, "&c你没有权限使用此命令。");
            this.reloadStarted = text(reloadStarted, "&7正在重载 ShitBot...");
            this.reloadSuccess = text(reloadSuccess, "&aShitBot 已完成热重载。");
            this.reloadFailed = text(reloadFailed, "&cShitBot 重载失败，旧配置仍在运行。");
        }

        public String getKickUnbound() { return kickUnbound; }
        public String getKickAfterUnbind() { return kickAfterUnbind; }
        public String getKickDatabaseUnavailable() { return kickDatabaseUnavailable; }
        public String getBindUsage() { return bindUsage; }
        public String getBindSuccess() { return bindSuccess; }
        public String getBindInvalid() { return bindInvalid; }
        public String getBindExpired() { return bindExpired; }
        public String getBindQqAlreadyUsed() { return bindQqAlreadyUsed; }
        public String getBindQqLimitReached() { return bindQqLimitReached; }
        public String getBindPlayerAlreadyUsed() { return bindPlayerAlreadyUsed; }
        public String getBindDatabaseError() { return bindDatabaseError; }
        public String getOnlineFailed() { return onlineFailed; }
        public String getInventoryNotBound() { return inventoryNotBound; }
        public String getInventoryPlayerNotBound() { return inventoryPlayerNotBound; }
        public String getInventoryUnavailable() { return inventoryUnavailable; }
        public String getInventoryDisabled() { return inventoryDisabled; }
        public String getInventoryFailed() { return inventoryFailed; }
        public String getNoPermission() { return noPermission; }
        public String getReloadStarted() { return reloadStarted; }
        public String getReloadSuccess() { return reloadSuccess; }
        public String getReloadFailed() { return reloadFailed; }
    }

    private static int clamp(int value, int minimum, int maximum, int fallback) {
        int actual = value <= 0 ? fallback : value;
        return Math.max(minimum, Math.min(maximum, actual));
    }

    private static long clampLong(long value, long minimum, long maximum, long fallback) {
        long actual = value <= 0L ? fallback : value;
        return Math.max(minimum, Math.min(maximum, actual));
    }
}
