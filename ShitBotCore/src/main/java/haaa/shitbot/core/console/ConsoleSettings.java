package haaa.shitbot.core.console;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

public final class ConsoleSettings {
    private final boolean enabled;
    private final int requestTimeoutSeconds;
    private final int commandCooldownSeconds;
    private final String notBoundMessage;
    private final String noPermissionMessage;
    private final String unavailableMessage;
    private final String invalidTargetMessage;
    private final BackendTransport backendTransport;
    private final Tps tps;
    private final List<Shortcut> shortcuts;

    public ConsoleSettings(boolean enabled,
                           int requestTimeoutSeconds,
                           int commandCooldownSeconds,
                           String notBoundMessage,
                           String noPermissionMessage,
                           String unavailableMessage,
                           String invalidTargetMessage,
                           BackendTransport backendTransport,
                           Tps tps,
                           List<Shortcut> shortcuts) {
        this.enabled = enabled;
        this.requestTimeoutSeconds = clamp(requestTimeoutSeconds, 2, 60, 15);
        this.commandCooldownSeconds = clamp(commandCooldownSeconds, 0, 300, 5);
        this.notBoundMessage = text(notBoundMessage, "%at% 请先绑定游戏 ID。");
        this.noPermissionMessage = text(noPermissionMessage, "%at% 你绑定的角色没有权限执行该操作。");
        this.unavailableMessage = text(unavailableMessage, "%at% 没有可用的子服，或请求执行超时。");
        this.invalidTargetMessage = text(invalidTargetMessage, "%at% 用法：%command% [目标子服]");
        this.backendTransport = backendTransport == null ? BackendTransport.disabled() : backendTransport;
        this.tps = tps == null ? Tps.disabled() : tps;
        this.shortcuts = shortcuts == null
                ? Collections.<Shortcut>emptyList()
                : Collections.unmodifiableList(new ArrayList<Shortcut>(shortcuts));
    }

    public boolean isEnabled() { return enabled; }
    public int getRequestTimeoutSeconds() { return requestTimeoutSeconds; }
    public int getCommandCooldownSeconds() { return commandCooldownSeconds; }
    public String getNotBoundMessage() { return notBoundMessage; }
    public String getNoPermissionMessage() { return noPermissionMessage; }
    public String getUnavailableMessage() { return unavailableMessage; }
    public String getInvalidTargetMessage() { return invalidTargetMessage; }
    public BackendTransport getBackendTransport() { return backendTransport; }
    public Tps getTps() { return tps; }
    public List<Shortcut> getShortcuts() { return shortcuts; }

    public enum Target {
        BACKEND,
        PROXY;

        public static Target from(String value) {
            return value != null && "proxy".equalsIgnoreCase(value.trim()) ? PROXY : BACKEND;
        }
    }

    public static final class Shortcut {
        private final String name;
        private final boolean enabled;
        private final List<String> aliases;
        private final String command;
        private final String permission;
        private final Target target;
        private final String server;
        private final int captureSeconds;
        private final String successMessage;
        private final String failedMessage;

        public Shortcut(String name,
                        boolean enabled,
                        List<String> aliases,
                        String command,
                        String permission,
                        Target target,
                        String server,
                        int captureSeconds,
                        String successMessage,
                        String failedMessage) {
            this.name = text(name, "shortcut");
            this.enabled = enabled;
            this.aliases = cleanAliases(aliases);
            this.command = command == null ? "" : command.trim();
            this.permission = permission == null ? "" : permission.trim();
            this.target = target == null ? Target.BACKEND : target;
            this.server = server == null ? "" : server.trim();
            this.captureSeconds = clamp(captureSeconds, 1, 30, 5);
            this.successMessage = text(successMessage, "执行成功（%source%）：\n%result%");
            this.failedMessage = text(failedMessage, "执行失败（%source%）：\n%result%");
        }

        public String getName() { return name; }
        public boolean isEnabled() { return enabled; }
        public List<String> getAliases() { return aliases; }
        public String getCommand() { return command; }
        public String getPermission() { return permission; }
        public Target getTarget() { return target; }
        public String getServer() { return server; }
        public int getCaptureSeconds() { return captureSeconds; }
        public String getSuccessMessage() { return successMessage; }
        public String getFailedMessage() { return failedMessage; }
    }

    public static final class Tps {
        private final boolean enabled;
        private final List<String> aliases;
        private final String permission;
        private final String server;
        private final String successMessage;
        private final String failedMessage;

        public Tps(boolean enabled,
                   List<String> aliases,
                   String permission,
                   String server,
                   String successMessage,
                   String failedMessage) {
            this.enabled = enabled;
            this.aliases = cleanAliases(aliases);
            this.permission = permission == null ? "" : permission.trim();
            this.server = server == null ? "" : server.trim();
            this.successMessage = text(successMessage, "服务器 TPS（%source%）：%result%");
            this.failedMessage = text(failedMessage, "TPS 获取失败：%result%");
        }

        private static Tps disabled() {
            return new Tps(false, Collections.<String>emptyList(), "", "", "", "");
        }

        public boolean isEnabled() { return enabled; }
        public List<String> getAliases() { return aliases; }
        public String getPermission() { return permission; }
        public String getServer() { return server; }
        public String getSuccessMessage() { return successMessage; }
        public String getFailedMessage() { return failedMessage; }
    }

    public static final class BackendTransport {
        private final int connectTimeoutMillis;
        private final int readTimeoutSeconds;
        private final String defaultServer;
        private final BackendListener listener;
        private final Map<String, BackendEndpoint> endpoints;

        public BackendTransport(int connectTimeoutMillis,
                                int readTimeoutSeconds,
                                String defaultServer,
                                BackendListener listener,
                                List<BackendEndpoint> endpoints) {
            this.connectTimeoutMillis = clamp(connectTimeoutMillis, 250, 15000, 3000);
            this.readTimeoutSeconds = clamp(readTimeoutSeconds, 3, 90, 40);
            this.defaultServer = defaultServer == null ? "" : defaultServer.trim();
            this.listener = listener == null ? BackendListener.disabled() : listener;
            Map<String, BackendEndpoint> endpointMap = new LinkedHashMap<String, BackendEndpoint>();
            if (endpoints != null) {
                for (BackendEndpoint endpoint : endpoints) {
                    if (endpoint != null && !endpoint.getName().isEmpty()) {
                        endpointMap.put(endpoint.getName().toLowerCase(Locale.ROOT), endpoint);
                    }
                }
            }
            this.endpoints = Collections.unmodifiableMap(endpointMap);
        }

        private static BackendTransport disabled() {
            return new BackendTransport(3000, 40, "", null, null);
        }

        public int getConnectTimeoutMillis() { return connectTimeoutMillis; }
        public int getReadTimeoutSeconds() { return readTimeoutSeconds; }
        public String getDefaultServer() { return defaultServer; }
        public BackendListener getListener() { return listener; }
        public Map<String, BackendEndpoint> getEndpoints() { return endpoints; }

        public BackendEndpoint getEndpoint(String serverName) {
            return serverName == null ? null : endpoints.get(serverName.trim().toLowerCase(Locale.ROOT));
        }

        public BackendEndpoint getOnlyEndpoint() {
            return endpoints.size() == 1 ? endpoints.values().iterator().next() : null;
        }
    }

    public static final class BackendListener {
        private final boolean enabled;
        private final String bindAddress;
        private final int port;
        private final String token;
        private final String serverName;

        public BackendListener(boolean enabled, String bindAddress, int port, String token, String serverName) {
            this.enabled = enabled;
            this.bindAddress = text(bindAddress, "127.0.0.1");
            this.port = clamp(port, 1, 65535, 25580);
            this.token = token == null ? "" : token.trim();
            this.serverName = text(serverName, "backend");
        }

        private static BackendListener disabled() {
            return new BackendListener(false, "127.0.0.1", 25580, "", "backend");
        }

        public boolean isEnabled() { return enabled; }
        public String getBindAddress() { return bindAddress; }
        public int getPort() { return port; }
        public String getToken() { return token; }
        public String getServerName() { return serverName; }
    }

    public static final class BackendEndpoint {
        private final String name;
        private final String host;
        private final int port;
        private final String token;

        public BackendEndpoint(String name, String host, int port, String token) {
            this.name = name == null ? "" : name.trim();
            this.host = text(host, "127.0.0.1");
            this.port = clamp(port, 1, 65535, 25580);
            this.token = token == null ? "" : token.trim();
        }

        public String getName() { return name; }
        public String getHost() { return host; }
        public int getPort() { return port; }
        public String getToken() { return token; }
    }

    private static List<String> cleanAliases(List<String> aliases) {
        if (aliases == null || aliases.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> result = new ArrayList<String>();
        for (String alias : aliases) {
            if (alias != null && !alias.trim().isEmpty() && !result.contains(alias.trim())) {
                result.add(alias.trim());
            }
        }
        return Collections.unmodifiableList(result);
    }

    private static String text(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value;
    }

    private static int clamp(int value, int minimum, int maximum, int fallback) {
        if (value < minimum || value > maximum) {
            return fallback;
        }
        return value;
    }
}
