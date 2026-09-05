package haaa.shitbot.core.console;

import java.util.ArrayList;
import java.util.Arrays;
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
    private final String resultTimeoutMessage;
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
                           String resultTimeoutMessage,
                           String invalidTargetMessage,
                           BackendTransport backendTransport,
                           Tps tps,
                           List<Shortcut> shortcuts) {
        this.enabled = enabled;
        this.requestTimeoutSeconds = clamp(requestTimeoutSeconds, 2, 60, 15);
        this.commandCooldownSeconds = clamp(commandCooldownSeconds, 0, 300, 5);
        this.notBoundMessage = text(notBoundMessage, "%at%");
        this.noPermissionMessage = text(noPermissionMessage, "%at%");
        this.unavailableMessage = text(unavailableMessage, "%at%");
        this.resultTimeoutMessage = text(resultTimeoutMessage, "%at% %result%");
        this.invalidTargetMessage = text(invalidTargetMessage, "%at% %command%");
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
    public String getResultTimeoutMessage() { return resultTimeoutMessage; }
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
        private final boolean allowUnbound;
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
                        boolean allowUnbound,
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
            this.allowUnbound = allowUnbound;
            this.target = target == null ? Target.BACKEND : target;
            this.server = server == null ? "" : server.trim();
            this.captureSeconds = clamp(captureSeconds, 1, 30, 5);
            this.successMessage = text(successMessage, "%result%");
            this.failedMessage = text(failedMessage, "%result%");
        }

        public String getName() { return name; }
        public boolean isEnabled() { return enabled; }
        public List<String> getAliases() { return aliases; }
        public String getCommand() { return command; }
        public String getPermission() { return permission; }
        public boolean isAllowUnbound() { return allowUnbound; }
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
            this.successMessage = text(successMessage, "%result%");
            this.failedMessage = text(failedMessage, "%result%");
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
        private final int authenticationTimeoutMillis;
        private final List<String> allowedProxyAddresses;
        private final boolean allowInsecureRemotePlaintext;
        private final boolean tlsEnabled;
        private final String tlsKeyStore;
        private final String tlsKeyStorePassword;
        private final String tlsTrustStore;
        private final String tlsTrustStorePassword;
        private final boolean tlsRequireClientCertificate;

        public BackendListener(boolean enabled,
                               String bindAddress,
                               int port,
                               String token,
                               String serverName,
                               int authenticationTimeoutMillis,
                               List<String> allowedProxyAddresses,
                               boolean allowInsecureRemotePlaintext,
                               boolean tlsEnabled,
                               String tlsKeyStore,
                               String tlsKeyStorePassword,
                               String tlsTrustStore,
                               String tlsTrustStorePassword,
                               boolean tlsRequireClientCertificate) {
            this.enabled = enabled;
            this.bindAddress = text(bindAddress, "127.0.0.1");
            this.port = clamp(port, 1, 65535, 25580);
            this.token = token == null ? "" : token.trim();
            this.serverName = text(serverName, "backend");
            this.authenticationTimeoutMillis = clamp(authenticationTimeoutMillis, 500, 5000, 2000);
            List<String> cleanedAddresses = cleanAliases(allowedProxyAddresses);
            this.allowedProxyAddresses = cleanedAddresses.isEmpty()
                    ? Collections.unmodifiableList(Arrays.asList("127.0.0.1", "::1"))
                    : cleanedAddresses;
            this.allowInsecureRemotePlaintext = allowInsecureRemotePlaintext;
            this.tlsEnabled = tlsEnabled;
            this.tlsKeyStore = tlsKeyStore == null ? "" : tlsKeyStore.trim();
            this.tlsKeyStorePassword = tlsKeyStorePassword == null ? "" : tlsKeyStorePassword;
            this.tlsTrustStore = tlsTrustStore == null ? "" : tlsTrustStore.trim();
            this.tlsTrustStorePassword = tlsTrustStorePassword == null ? "" : tlsTrustStorePassword;
            this.tlsRequireClientCertificate = tlsRequireClientCertificate;
        }

        private static BackendListener disabled() {
            return new BackendListener(false, "127.0.0.1", 25580, "", "backend",
                    2000, Arrays.asList("127.0.0.1", "::1"), false,
                    false, "", "", "", "", false);
        }

        public boolean isEnabled() { return enabled; }
        public String getBindAddress() { return bindAddress; }
        public int getPort() { return port; }
        public String getToken() { return token; }
        public String getServerName() { return serverName; }
        public int getAuthenticationTimeoutMillis() { return authenticationTimeoutMillis; }
        public List<String> getAllowedProxyAddresses() { return allowedProxyAddresses; }
        public boolean isAllowInsecureRemotePlaintext() { return allowInsecureRemotePlaintext; }
        public boolean isTlsEnabled() { return tlsEnabled; }
        public String getTlsKeyStore() { return tlsKeyStore; }
        public String getTlsKeyStorePassword() { return tlsKeyStorePassword; }
        public String getTlsTrustStore() { return tlsTrustStore; }
        public String getTlsTrustStorePassword() { return tlsTrustStorePassword; }
        public boolean isTlsRequireClientCertificate() { return tlsRequireClientCertificate; }
    }

    public static final class BackendEndpoint {
        private final String name;
        private final String host;
        private final int port;
        private final String token;
        private final boolean allowInsecureRemotePlaintext;
        private final boolean tlsEnabled;
        private final String tlsTrustStore;
        private final String tlsTrustStorePassword;
        private final String tlsKeyStore;
        private final String tlsKeyStorePassword;

        public BackendEndpoint(String name,
                               String host,
                               int port,
                               String token,
                               boolean allowInsecureRemotePlaintext,
                               boolean tlsEnabled,
                               String tlsTrustStore,
                               String tlsTrustStorePassword,
                               String tlsKeyStore,
                               String tlsKeyStorePassword) {
            this.name = name == null ? "" : name.trim();
            this.host = text(host, "127.0.0.1");
            this.port = clamp(port, 1, 65535, 25580);
            this.token = token == null ? "" : token.trim();
            this.allowInsecureRemotePlaintext = allowInsecureRemotePlaintext;
            this.tlsEnabled = tlsEnabled;
            this.tlsTrustStore = tlsTrustStore == null ? "" : tlsTrustStore.trim();
            this.tlsTrustStorePassword = tlsTrustStorePassword == null ? "" : tlsTrustStorePassword;
            this.tlsKeyStore = tlsKeyStore == null ? "" : tlsKeyStore.trim();
            this.tlsKeyStorePassword = tlsKeyStorePassword == null ? "" : tlsKeyStorePassword;
        }

        public String getName() { return name; }
        public String getHost() { return host; }
        public int getPort() { return port; }
        public String getToken() { return token; }
        public boolean isAllowInsecureRemotePlaintext() { return allowInsecureRemotePlaintext; }
        public boolean isTlsEnabled() { return tlsEnabled; }
        public String getTlsTrustStore() { return tlsTrustStore; }
        public String getTlsTrustStorePassword() { return tlsTrustStorePassword; }
        public String getTlsKeyStore() { return tlsKeyStore; }
        public String getTlsKeyStorePassword() { return tlsKeyStorePassword; }
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
