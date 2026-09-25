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
    private final boolean debug;
    private final Translations translations;
    private final OneBot oneBot;
    private final Forwarding forwarding;
    private final Binding binding;
    private final Database database;
    private final Image image;
    private final CustomImages customImages;
    private final Inventory inventory;
    private final Messages messages;

    public Settings(int configVersion,
                    Translations translations,
                    OneBot oneBot,
                    Forwarding forwarding,
                    Binding binding,
                    Database database,
                    Image image,
                    CustomImages customImages,
                    Inventory inventory,
                    Messages messages) {
        this(configVersion, false, translations, oneBot, forwarding, binding,
                database, image, customImages, inventory, messages);
    }

    public Settings(int configVersion,
                    boolean debug,
                    Translations translations,
                    OneBot oneBot,
                    Forwarding forwarding,
                    Binding binding,
                    Database database,
                    Image image,
                    CustomImages customImages,
                    Inventory inventory,
                    Messages messages) {
        this.configVersion = configVersion;
        this.debug = debug;
        this.translations = require(translations, "translations");
        this.oneBot = require(oneBot, "oneBot");
        this.forwarding = require(forwarding, "forwarding");
        this.binding = require(binding, "binding");
        this.database = require(database, "database");
        this.image = require(image, "image");
        this.customImages = require(customImages, "customImages");
        this.inventory = require(inventory, "inventory");
        this.messages = require(messages, "messages");
    }

    public int getConfigVersion() {
        return configVersion;
    }

    public boolean isDebug() {
        return debug;
    }

    public Translations getTranslations() {
        return translations;
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

    public CustomImages getCustomImages() {
        return customImages;
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
        private final boolean allowInsecureRemoteWebsocket;
        private final List<Long> allowedGroupIds;
        private final boolean allowAllGroups;
        private final int connectTimeoutSeconds;
        private final int actionTimeoutSeconds;
        private final int maximumPendingActions;
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
        private final ServerStartupNotice serverStartupNotice;

        public OneBot(boolean enabled,
                      String websocketUrl,
                      String accessToken,
                      boolean allowInsecureRemoteWebsocket,
                      List<Long> allowedGroupIds,
                      boolean allowAllGroups,
                      int connectTimeoutSeconds,
                      int actionTimeoutSeconds,
                      int maximumPendingActions,
                      int heartbeatTimeoutSeconds,
                      int reconnectInitialSeconds,
                      int reconnectMaximumSeconds,
                      int commandCooldownSeconds,
                      boolean replyAtSender,
                      Command bindCommand,
                      Command onlineImageCommand,
                      Command inventoryCommand,
                      GroupJoinWelcome groupJoinWelcome,
                      GroupLeaveUnbind groupLeaveUnbind,
                      ServerStartupNotice serverStartupNotice) {
            this.enabled = enabled;
            this.websocketUrl = text(websocketUrl, "ws://127.0.0.1:3001");
            this.accessToken = accessToken == null ? "" : accessToken.trim();
            this.allowInsecureRemoteWebsocket = allowInsecureRemoteWebsocket;
            this.allowedGroupIds = allowedGroupIds == null
                    ? Collections.<Long>emptyList()
                    : Collections.unmodifiableList(new ArrayList<Long>(allowedGroupIds));
            this.allowAllGroups = allowAllGroups;
            this.connectTimeoutSeconds = clamp(connectTimeoutSeconds, 1, 60, 10);
            this.actionTimeoutSeconds = clamp(actionTimeoutSeconds, 1, 120, 15);
            this.maximumPendingActions = clamp(maximumPendingActions, 16, 4096, 256);
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
            this.serverStartupNotice = require(serverStartupNotice, "serverStartupNotice");
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

        public boolean isAllowInsecureRemoteWebsocket() {
            return allowInsecureRemoteWebsocket;
        }

        public List<Long> getAllowedGroupIds() {
            return allowedGroupIds;
        }

        public boolean isAllowAllGroups() {
            return allowAllGroups;
        }

        public int getConnectTimeoutSeconds() {
            return connectTimeoutSeconds;
        }

        public int getActionTimeoutSeconds() {
            return actionTimeoutSeconds;
        }

        public int getMaximumPendingActions() {
            return maximumPendingActions;
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

        public ServerStartupNotice getServerStartupNotice() {
            return serverStartupNotice;
        }

        public boolean isGroupAllowed(long groupId) {
            return allowAllGroups || allowedGroupIds.contains(Long.valueOf(groupId));
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
            this.message = text(message, "");
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

    public static final class ServerStartupNotice {
        private final boolean enabled;
        private final String targetServer;
        private final int checkIntervalSeconds;
        private final String message;

        public ServerStartupNotice(boolean enabled,
                                   String targetServer,
                                   int checkIntervalSeconds,
                                   String message) {
            this.enabled = enabled;
            this.targetServer = targetServer == null ? "" : targetServer.trim();
            this.checkIntervalSeconds = clamp(checkIntervalSeconds, 1, 300, 5);
            this.message = text(message, "");
        }

        public boolean isEnabled() {
            return enabled;
        }

        public String getTargetServer() {
            return targetServer;
        }

        public int getCheckIntervalSeconds() {
            return checkIntervalSeconds;
        }

        public String getMessage() {
            return message;
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
        private final int maximumAttemptsPerQq;
        private final int maximumTotalAttempts;
        private final int totalAttemptCooldownSeconds;
        private final int loginDatabaseTimeoutSeconds;
        private final boolean allowMultipleIdsPerQq;
        private final int maximumIdsPerQq;
        private final String codeAlphabet;

        public Binding(boolean enabled,
                       int codeLength,
                       int expireMinutes,
                       int maximumAttemptsPerQq,
                       int maximumTotalAttempts,
                       int totalAttemptCooldownSeconds,
                       int loginDatabaseTimeoutSeconds,
                       boolean allowMultipleIdsPerQq,
                       int maximumIdsPerQq,
                       String codeAlphabet) {
            this.enabled = enabled;
            this.codeLength = clamp(codeLength, 4, 12, 6);
            this.expireMinutes = clamp(expireMinutes, 1, 1440, 10);
            this.maximumAttemptsPerQq = clamp(maximumAttemptsPerQq, 1, 20, 5);
            this.maximumTotalAttempts = Math.max(
                    this.maximumAttemptsPerQq * 4,
                    clamp(maximumTotalAttempts, 1, 100, 30));
            this.totalAttemptCooldownSeconds = clamp(totalAttemptCooldownSeconds, 10, 600, 30);
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

        public int getMaximumAttemptsPerQq() {
            return maximumAttemptsPerQq;
        }

        public int getMaximumTotalAttempts() {
            return maximumTotalAttempts;
        }

        public int getTotalAttemptCooldownSeconds() {
            return totalAttemptCooldownSeconds;
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
        private final boolean allowInsecureRemoteMysql;
        private final int mysqlConnectTimeoutMs;
        private final int mysqlSocketTimeoutMs;
        private final int maximumPoolSize;
        private final int minimumIdle;
        private final long connectionTimeoutMs;
        private final long validationTimeoutMs;
        private final long idleTimeoutMs;
        private final long maximumLifetimeMs;
        private final long keepaliveTimeMs;
        private final int asyncThreads;
        private final int maximumQueuedTasks;

        public Database(Type type,
                        String sqliteFile,
                        String mysqlHost,
                        int mysqlPort,
                        String mysqlDatabase,
                        String mysqlUsername,
                        String mysqlPassword,
                        String mysqlParameters,
                        boolean allowInsecureRemoteMysql,
                        int mysqlConnectTimeoutMs,
                        int mysqlSocketTimeoutMs,
                        int maximumPoolSize,
                        int minimumIdle,
                        long connectionTimeoutMs,
                        long validationTimeoutMs,
                        long idleTimeoutMs,
                        long maximumLifetimeMs,
                        long keepaliveTimeMs,
                        int asyncThreads,
                        int maximumQueuedTasks) {
            this.type = type == null ? Type.SQLITE : type;
            this.sqliteFile = sanitizeFileName(sqliteFile, "shitbot.db");
            this.mysqlHost = text(mysqlHost, "127.0.0.1");
            this.mysqlPort = clamp(mysqlPort, 1, 65535, 3306);
            this.mysqlDatabase = text(mysqlDatabase, "shitbot");
            this.mysqlUsername = text(mysqlUsername, "shitbot");
            this.mysqlPassword = mysqlPassword == null ? "" : mysqlPassword;
            this.mysqlParameters = text(mysqlParameters,
                    "useUnicode=true&characterEncoding=utf8&sslMode=DISABLED&serverTimezone=Asia/Tokyo&allowPublicKeyRetrieval=false");
            this.allowInsecureRemoteMysql = allowInsecureRemoteMysql;
            this.mysqlConnectTimeoutMs = clamp(mysqlConnectTimeoutMs, 1000, 120000, 5000);
            this.mysqlSocketTimeoutMs = clamp(mysqlSocketTimeoutMs, 1000, 300000, 15000);
            this.maximumPoolSize = clamp(maximumPoolSize, 1, 50, 10);
            this.minimumIdle = clamp(minimumIdle, 0, this.maximumPoolSize, 2);
            this.connectionTimeoutMs = clampLong(connectionTimeoutMs, 1000L, 120000L, 5000L);
            this.validationTimeoutMs = clampLong(validationTimeoutMs, 500L, this.connectionTimeoutMs, 3000L);
            this.idleTimeoutMs = clampLong(idleTimeoutMs, 10000L, 1800000L, 600000L);
            this.maximumLifetimeMs = clampLong(maximumLifetimeMs, 30000L, 3600000L, 1800000L);
            long keepaliveFallback = this.maximumLifetimeMs > 301000L ? 300000L : 0L;
            this.keepaliveTimeMs = clampLong(
                    keepaliveTimeMs, 0L, this.maximumLifetimeMs - 1000L, keepaliveFallback);
            this.asyncThreads = clamp(asyncThreads, 1, 16, 2);
            this.maximumQueuedTasks = clamp(maximumQueuedTasks, 1, 65536, 256);
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

        public boolean isAllowInsecureRemoteMysql() {
            return allowInsecureRemoteMysql;
        }

        public int getMysqlConnectTimeoutMs() {
            return mysqlConnectTimeoutMs;
        }

        public int getMysqlSocketTimeoutMs() {
            return mysqlSocketTimeoutMs;
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

        public int getMaximumQueuedTasks() {
            return maximumQueuedTasks;
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
        public enum Renderer {
            JAVA,
            CUSTOM;

            public static Renderer from(String value) {
                return value != null && "custom".equalsIgnoreCase(value.trim()) ? CUSTOM : JAVA;
            }
        }

        private final ImageTemplate template;
        private final Renderer renderer;
        private final String customTemplateId;
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

        public Image(ImageTemplate template,
                     Renderer renderer,
                     String customTemplateId,
                     String title,
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
            this.template = require(template, "imageTemplate");
            this.renderer = require(renderer, "imageRenderer");
            this.customTemplateId = templateId(customTemplateId, "online-status");
            this.title = text(title, "ShitBot");
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

        public ImageTemplate getTemplate() {
            return template;
        }

        public Renderer getRenderer() {
            return renderer;
        }

        public String getCustomTemplateId() {
            return customTemplateId;
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

        private static String templateId(String value, String fallback) {
            String id = text(value, fallback);
            if (!id.matches("[a-z0-9][a-z0-9_-]{0,63}")) {
                throw new IllegalArgumentException("Invalid custom image template ID: " + value);
            }
            return id;
        }
    }

    public static final class CustomImages {
        private final boolean enabled;
        private final String componentVersion;
        private final String componentDownloadUrl;
        private final long maximumDownloadBytes;
        private final int downloadConnectTimeoutMs;
        private final int downloadReadTimeoutMs;
        private final String templatesDirectory;
        private final int maximumWidth;
        private final int maximumHeight;
        private final long maximumPixels;
        private final int maximumLayers;
        private final int maximumLoopItems;
        private final long maximumAssetBytes;
        private final long maximumTemplateAssetBytes;
        private final int renderTimeoutMs;
        private final int renderThreads;
        private final int maximumQueuedRenders;
        private final boolean remoteImagesEnabled;
        private final int maximumProviderQueries;
        private final int providerTimeoutMs;
        private final int providerCacheSeconds;
        private final boolean editorEnabled;
        private final String editorBindAddress;
        private final int editorPort;
        private final int editorLoginSeconds;
        private final long editorMaximumUploadBytes;

        public CustomImages(boolean enabled,
                            String componentVersion,
                            String componentDownloadUrl,
                            long maximumDownloadBytes,
                            int downloadConnectTimeoutMs,
                            int downloadReadTimeoutMs,
                            String templatesDirectory,
                            int maximumWidth,
                            int maximumHeight,
                            long maximumPixels,
                            int maximumLayers,
                            int maximumLoopItems,
                            long maximumAssetBytes,
                            long maximumTemplateAssetBytes,
                            int renderTimeoutMs,
                            int renderThreads,
                            int maximumQueuedRenders,
                            boolean remoteImagesEnabled,
                            int maximumProviderQueries,
                            int providerTimeoutMs,
                            int providerCacheSeconds,
                            boolean editorEnabled,
                            String editorBindAddress,
                            int editorPort,
                            int editorLoginSeconds,
                            long editorMaximumUploadBytes) {
            this.enabled = enabled;
            this.componentVersion = componentVersion == null ? "" : componentVersion.trim();
            this.componentDownloadUrl = text(componentDownloadUrl,
                    "https://github.com/hutuyee/ShitBot/releases/download/%version%/ShitBotRenderer-%version%.jar");
            this.maximumDownloadBytes = clampLong(maximumDownloadBytes,
                    256L * 1024L, 32L * 1024L * 1024L, 4L * 1024L * 1024L);
            this.downloadConnectTimeoutMs = clamp(downloadConnectTimeoutMs, 500, 30000, 5000);
            this.downloadReadTimeoutMs = clamp(downloadReadTimeoutMs, 1000, 120000, 30000);
            this.templatesDirectory = safeRelativeDirectory(templatesDirectory, "image-templates");
            this.maximumWidth = clamp(maximumWidth, 64, 8192, 2400);
            this.maximumHeight = clamp(maximumHeight, 64, 8192, 2400);
            this.maximumPixels = clampLong(maximumPixels, 4096L, 32L * 1024L * 1024L, 8L * 1024L * 1024L);
            this.maximumLayers = clamp(maximumLayers, 1, 4096, 256);
            this.maximumLoopItems = clamp(maximumLoopItems, 1, 2048, 200);
            this.maximumAssetBytes = clampLong(maximumAssetBytes,
                    1024L, 32L * 1024L * 1024L, 2L * 1024L * 1024L);
            this.maximumTemplateAssetBytes = Math.max(this.maximumAssetBytes,
                    clampLong(maximumTemplateAssetBytes, 1024L,
                            128L * 1024L * 1024L, 16L * 1024L * 1024L));
            this.renderTimeoutMs = clamp(renderTimeoutMs, 100, 60000, 5000);
            this.renderThreads = clamp(renderThreads, 1, 16, 2);
            this.maximumQueuedRenders = clamp(maximumQueuedRenders, 1, 512, 16);
            this.remoteImagesEnabled = remoteImagesEnabled;
            this.maximumProviderQueries = clamp(maximumProviderQueries, 1, 256, 32);
            this.providerTimeoutMs = clamp(providerTimeoutMs, 100, 30000, 3000);
            this.providerCacheSeconds = clamp(providerCacheSeconds, 0, 3600, 10);
            this.editorEnabled = editorEnabled;
            this.editorBindAddress = text(editorBindAddress, "127.0.0.1");
            this.editorPort = clamp(editorPort, 0, 65535, 0);
            this.editorLoginSeconds = clamp(editorLoginSeconds, 10, 600, 60);
            this.editorMaximumUploadBytes = clampLong(editorMaximumUploadBytes,
                    1024L, 16L * 1024L * 1024L, 2L * 1024L * 1024L);
        }

        public boolean isEnabled() { return enabled; }
        public String getComponentVersion() { return componentVersion; }
        public String getComponentDownloadUrl() { return componentDownloadUrl; }
        public long getMaximumDownloadBytes() { return maximumDownloadBytes; }
        public int getDownloadConnectTimeoutMs() { return downloadConnectTimeoutMs; }
        public int getDownloadReadTimeoutMs() { return downloadReadTimeoutMs; }
        public String getTemplatesDirectory() { return templatesDirectory; }
        public int getMaximumWidth() { return maximumWidth; }
        public int getMaximumHeight() { return maximumHeight; }
        public long getMaximumPixels() { return maximumPixels; }
        public int getMaximumLayers() { return maximumLayers; }
        public int getMaximumLoopItems() { return maximumLoopItems; }
        public long getMaximumAssetBytes() { return maximumAssetBytes; }
        public long getMaximumTemplateAssetBytes() { return maximumTemplateAssetBytes; }
        public int getRenderTimeoutMs() { return renderTimeoutMs; }
        public int getRenderThreads() { return renderThreads; }
        public int getMaximumQueuedRenders() { return maximumQueuedRenders; }
        public boolean isRemoteImagesEnabled() { return remoteImagesEnabled; }
        public int getMaximumProviderQueries() { return maximumProviderQueries; }
        public int getProviderTimeoutMs() { return providerTimeoutMs; }
        public int getProviderCacheSeconds() { return providerCacheSeconds; }
        public boolean isEditorEnabled() { return editorEnabled; }
        public String getEditorBindAddress() { return editorBindAddress; }
        public int getEditorPort() { return editorPort; }
        public int getEditorLoginSeconds() { return editorLoginSeconds; }
        public long getEditorMaximumUploadBytes() { return editorMaximumUploadBytes; }

        private static String safeRelativeDirectory(String value, String fallback) {
            String path = text(value, fallback).replace('\\', '/');
            if (path.startsWith("/") || path.matches("^[A-Za-z]:.*")
                    || path.equals("..") || path.startsWith("../") || path.contains("/../")) {
                throw new IllegalArgumentException("Custom image templates directory must stay inside the plugin directory");
            }
            for (String segment : path.split("/")) {
                if (".".equals(segment) || "..".equals(segment)) {
                    throw new IllegalArgumentException(
                            "Custom image templates directory must not contain dot segments");
                }
            }
            return path;
        }
    }

    public static final class Inventory {
        private final ImageTemplate template;
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
        private final int maximumQueuedRenders;
        private final String outputFile;
        private final String exportedIconsDirectory;
        private final boolean scanModJars;
        private final String modsDirectory;
        private final boolean autoDiscoverResources;
        private final int resourceRefreshSeconds;
        private final int resourceIndexWaitMs;
        private final List<String> resourceArchives;
        private final int iconCacheEntries;

        public Inventory(ImageTemplate template,
                         boolean enabled,
                         String title,
                         String fontName,
                         int width,
                         int slotSize,
                         int snapshotIntervalSeconds,
                         int snapshotRetentionDays,
                         int memoryMaximumEntries,
                         int renderCacheSeconds,
                         int maximumConcurrentRenders,
                         int maximumQueuedRenders,
                         String outputFile,
                         String exportedIconsDirectory,
                         boolean scanModJars,
                         String modsDirectory,
                         boolean autoDiscoverResources,
                         int resourceRefreshSeconds,
                         int resourceIndexWaitMs,
                         List<String> resourceArchives,
                         int iconCacheEntries) {
            this.template = require(template, "inventoryTemplate");
            this.enabled = enabled;
            this.title = text(title, "%player%");
            this.fontName = text(fontName, "Microsoft YaHei");
            this.width = clamp(width, 560, 2400, 760);
            this.slotSize = clamp(slotSize, 32, 96, 48);
            this.snapshotIntervalSeconds = clamp(snapshotIntervalSeconds, 15, 3600, 60);
            this.snapshotRetentionDays = clamp(snapshotRetentionDays, 1, 3650, 30);
            this.memoryMaximumEntries = clamp(memoryMaximumEntries, 64, 100000, 2048);
            this.renderCacheSeconds = Math.max(0, Math.min(300, renderCacheSeconds));
            this.maximumConcurrentRenders = clamp(maximumConcurrentRenders, 1, 8, 2);
            this.maximumQueuedRenders = clamp(maximumQueuedRenders, 1, 1024, 16);
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

        public ImageTemplate getTemplate() { return template; }
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
        public int getMaximumQueuedRenders() { return maximumQueuedRenders; }
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
            this.kickUnbound = text(kickUnbound, "");
            this.kickAfterUnbind = text(kickAfterUnbind, "");
            this.kickDatabaseUnavailable = text(kickDatabaseUnavailable, "");
            this.bindUsage = text(bindUsage, "");
            this.bindSuccess = text(bindSuccess, "");
            this.bindInvalid = text(bindInvalid, "");
            this.bindExpired = text(bindExpired, "");
            this.bindQqAlreadyUsed = text(bindQqAlreadyUsed, "");
            this.bindQqLimitReached = text(bindQqLimitReached, "");
            this.bindPlayerAlreadyUsed = text(bindPlayerAlreadyUsed, "");
            this.bindDatabaseError = text(bindDatabaseError, "");
            this.onlineFailed = text(onlineFailed, "");
            this.inventoryNotBound = text(inventoryNotBound, "");
            this.inventoryPlayerNotBound = text(inventoryPlayerNotBound, "");
            this.inventoryUnavailable = text(inventoryUnavailable, "");
            this.inventoryDisabled = text(inventoryDisabled, "");
            this.inventoryFailed = text(inventoryFailed, "");
            this.noPermission = text(noPermission, "");
            this.reloadStarted = text(reloadStarted, "");
            this.reloadSuccess = text(reloadSuccess, "");
            this.reloadFailed = text(reloadFailed, "");
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
        return value < minimum || value > maximum ? fallback : value;
    }

    private static long clampLong(long value, long minimum, long maximum, long fallback) {
        return value < minimum || value > maximum ? fallback : value;
    }
}
