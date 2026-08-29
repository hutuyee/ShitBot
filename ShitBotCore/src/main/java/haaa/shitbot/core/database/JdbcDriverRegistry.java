package haaa.shitbot.core.database;

import haaa.shitbot.core.config.Settings;
import haaa.shitbot.core.platform.PlatformBridge;
import haaa.shitbot.core.util.HashUtil;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.UUID;

/**
 * Resolves JDBC drivers from the server first and downloads a pinned fallback
 * only when the current server/proxy does not expose a compatible driver.
 */
final class JdbcDriverRegistry implements AutoCloseable {
    private static final String MAVEN_CENTRAL = "https://repo.maven.apache.org/maven2/";

    private static final Library SQLITE = new Library(
            "sqlite-jdbc-3.53.2.1.jar",
            MAVEN_CENTRAL + "org/xerial/sqlite-jdbc/3.53.2.1/sqlite-jdbc-3.53.2.1.jar",
            11982884L,
            "f55e405ed96d5ffe629e05b7b51b059e1c7d64527c0cc90a972fbac06730ccc1");
    private static final Library SLF4J_API = new Library(
            "slf4j-api-1.7.36.jar",
            MAVEN_CENTRAL + "org/slf4j/slf4j-api/1.7.36/slf4j-api-1.7.36.jar",
            41125L,
            "d3ef575e3e4979678dc01bf1dcce51021493b4d11fb7f1be8ad982877c16a1c0");
    private static final Library MYSQL = new Library(
            "mysql-connector-j-8.4.0.jar",
            MAVEN_CENTRAL + "com/mysql/mysql-connector-j/8.4.0/mysql-connector-j-8.4.0.jar",
            2533399L,
            "d77962877d010777cff997015da90ee689f0f4bb76848340e1488f2b83332af5");
    private static final Library PROTOBUF = new Library(
            "protobuf-java-3.25.1.jar",
            MAVEN_CENTRAL + "com/google/protobuf/protobuf-java/3.25.1/protobuf-java-3.25.1.jar",
            1875070L,
            "48a8e58a1a8f82eff141a7a388d38dfe77d7a48d5e57c9066ee37f19147e20df");

    private final Path libraryDirectory;
    private final PlatformBridge platform;

    private Driver sqliteDriver;
    private Driver mysqlDriver;
    private MysqlApi mysqlApi;
    private DriverClassLoader sqliteClassLoader;
    private DriverClassLoader mysqlClassLoader;

    JdbcDriverRegistry(Path dataDirectory, PlatformBridge platform) {
        this.libraryDirectory = dataDirectory.toAbsolutePath().normalize().resolve("libraries").normalize();
        this.platform = platform;
    }

    synchronized void prepare(Settings.Database settings) throws IOException, SQLException {
        if (settings.getType() == Settings.Database.Type.SQLITE) {
            resolveSqlite();
        } else {
            resolveMysql(settings.buildJdbcUrl(platform.getDataDirectory()));
        }
    }

    Connection openConfiguredConnection(Settings.Database settings) throws IOException, SQLException {
        if (settings.getType() == Settings.Database.Type.SQLITE) {
            return openSqlite(settings.buildJdbcUrl(platform.getDataDirectory()));
        }

        String url = settings.buildJdbcUrl(platform.getDataDirectory());
        ResolvedMysql resolved = resolveMysql(url);
        Properties properties = new Properties();
        properties.setProperty("user", settings.getMysqlUsername());
        properties.setProperty("password", settings.getMysqlPassword());
        properties.setProperty("cachePrepStmts", "true");
        properties.setProperty("prepStmtCacheSize", "250");
        properties.setProperty("prepStmtCacheSqlLimit", "2048");
        properties.setProperty("useServerPrepStmts", "true");
        properties.setProperty("rewriteBatchedStatements", "true");
        properties.setProperty("connectTimeout", String.valueOf(settings.getMysqlConnectTimeoutMs()));
        properties.setProperty("socketTimeout", String.valueOf(settings.getMysqlSocketTimeoutMs()));
        return connect(resolved.driver, resolved.jdbcUrl, properties);
    }

    Connection openSqlite(Path databasePath) throws IOException, SQLException {
        return openSqlite("jdbc:sqlite:" + databasePath.toAbsolutePath().normalize().toString());
    }

    private Connection openSqlite(String jdbcUrl) throws IOException, SQLException {
        return connect(resolveSqlite(), jdbcUrl, new Properties());
    }

    private synchronized Driver resolveSqlite() throws IOException, SQLException {
        if (sqliteDriver != null) {
            return sqliteDriver;
        }

        Driver coreDriver = tryCoreDriver("org.sqlite.JDBC");
        if (coreDriver != null) {
            sqliteDriver = coreDriver;
            logDriver("SQLite", coreDriver, "server");
            return coreDriver;
        }

        Path sqliteJar = ensureLibrary(SQLITE);
        Path slf4jJar = ensureLibrary(SLF4J_API);
        sqliteClassLoader = new DriverClassLoader(
                new URL[]{sqliteJar.toUri().toURL(), slf4jJar.toUri().toURL()},
                JdbcDriverRegistry.class.getClassLoader(),
                new String[]{"org.sqlite.", "org.slf4j."});
        sqliteDriver = instantiateFallbackDriver("org.sqlite.JDBC", sqliteClassLoader);
        logDriver("SQLite", sqliteDriver, "fallback " + libraryDirectory);
        return sqliteDriver;
    }

    private synchronized ResolvedMysql resolveMysql(String jdbcUrl) throws IOException, SQLException {
        if (mysqlDriver == null) {
            Driver modern = tryCoreDriver("com.mysql.cj.jdbc.NonRegisteringDriver");
            if (modern != null) {
                mysqlDriver = modern;
                mysqlApi = MysqlApi.MODERN;
                logDriver("MySQL", modern, "server (modern API)");
            } else {
                Driver legacy = tryCoreDriver("com.mysql.jdbc.NonRegisteringDriver");
                if (legacy != null && canUseLegacyMysql(jdbcUrl)) {
                    mysqlDriver = legacy;
                    mysqlApi = MysqlApi.LEGACY;
                    logDriver("MySQL", legacy, "server (legacy API)");
                } else {
                    if (legacy != null) {
                        platform.info("Server MySQL driver is too old for the configured sslMode; "
                                + "using the pinned modern fallback without weakening TLS verification");
                    }
                    loadFallbackMysql();
                }
            }
        }

        String resolvedUrl = mysqlApi == MysqlApi.LEGACY ? adaptLegacyMysqlUrl(jdbcUrl) : jdbcUrl;
        return new ResolvedMysql(mysqlDriver, resolvedUrl);
    }

    private void loadFallbackMysql() throws IOException, SQLException {
        Path mysqlJar = ensureLibrary(MYSQL);
        Path protobufJar = ensureLibrary(PROTOBUF);
        mysqlClassLoader = new DriverClassLoader(
                new URL[]{mysqlJar.toUri().toURL(), protobufJar.toUri().toURL()},
                JdbcDriverRegistry.class.getClassLoader(),
                new String[]{"com.mysql.", "com.google.protobuf."});
        mysqlDriver = instantiateFallbackDriver(
                "com.mysql.cj.jdbc.NonRegisteringDriver", mysqlClassLoader);
        mysqlApi = MysqlApi.MODERN;
        logDriver("MySQL", mysqlDriver, "fallback " + libraryDirectory);
    }

    private Driver tryCoreDriver(String className) {
        try {
            return instantiateDriver(className, JdbcDriverRegistry.class.getClassLoader());
        } catch (ClassNotFoundException exception) {
            return null;
        } catch (SQLException exception) {
            platform.warn("Server JDBC driver is present but cannot be created (" + className + "): "
                    + exception.getMessage());
            return null;
        } catch (LinkageError error) {
            platform.warn("Server JDBC driver is present but cannot be linked (" + className + "): "
                    + error.getClass().getSimpleName() + ": " + error.getMessage());
            return null;
        }
    }

    private Driver instantiateDriver(String className, ClassLoader classLoader)
            throws ClassNotFoundException, SQLException {
        try {
            Object value = Class.forName(className, true, classLoader).getDeclaredConstructor().newInstance();
            if (!(value instanceof Driver)) {
                throw new SQLException(className + " does not implement java.sql.Driver");
            }
            return (Driver) value;
        } catch (ClassNotFoundException exception) {
            throw exception;
        } catch (ReflectiveOperationException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof SQLException) {
                throw (SQLException) cause;
            }
            throw new SQLException("Unable to create JDBC driver " + className, cause == null ? exception : cause);
        }
    }

    private Driver instantiateFallbackDriver(String className, ClassLoader classLoader) throws SQLException {
        try {
            return instantiateDriver(className, classLoader);
        } catch (ClassNotFoundException exception) {
            throw new SQLException("Verified JDBC fallback does not contain " + className, exception);
        }
    }

    private Connection connect(Driver driver, String jdbcUrl, Properties properties) throws SQLException {
        Connection connection = driver.connect(jdbcUrl, properties);
        if (connection == null) {
            throw new SQLException("JDBC driver " + driver.getClass().getName()
                    + " did not accept URL " + redactJdbcUrl(jdbcUrl));
        }
        return connection;
    }

    private boolean canUseLegacyMysql(String jdbcUrl) {
        String sslMode = queryValue(jdbcUrl, "sslMode");
        if (sslMode == null || sslMode.trim().isEmpty()) {
            return true;
        }
        String normalized = sslMode.trim().toUpperCase(Locale.ROOT);
        return "DISABLED".equals(normalized)
                || "PREFERRED".equals(normalized)
                || "REQUIRED".equals(normalized);
    }

    /** Maps Connector/J 8 sslMode values that have exact Connector/J 5.1 equivalents. */
    private String adaptLegacyMysqlUrl(String jdbcUrl) {
        String sslMode = queryValue(jdbcUrl, "sslMode");
        if (sslMode == null || sslMode.trim().isEmpty()) {
            return jdbcUrl;
        }

        String normalized = sslMode.trim().toUpperCase(Locale.ROOT);
        List<String> parameters = queryParameters(jdbcUrl);
        removeParameter(parameters, "sslMode");
        // This Connector/J 8 RSA option is not understood by the 5.1 driver.
        removeParameter(parameters, "allowPublicKeyRetrieval");
        if ("DISABLED".equals(normalized)) {
            setParameter(parameters, "useSSL", "false");
            removeParameter(parameters, "requireSSL");
            removeParameter(parameters, "verifyServerCertificate");
        } else if ("PREFERRED".equals(normalized)) {
            setParameter(parameters, "useSSL", "true");
            setParameter(parameters, "requireSSL", "false");
            setParameter(parameters, "verifyServerCertificate", "false");
        } else if ("REQUIRED".equals(normalized)) {
            setParameter(parameters, "useSSL", "true");
            setParameter(parameters, "requireSSL", "true");
            setParameter(parameters, "verifyServerCertificate", "false");
        }
        int question = jdbcUrl.indexOf('?');
        String base = question < 0 ? jdbcUrl : jdbcUrl.substring(0, question);
        return parameters.isEmpty() ? base : base + '?' + join(parameters, "&");
    }

    private Path ensureLibrary(Library library) throws IOException {
        Files.createDirectories(libraryDirectory);
        Path target = libraryDirectory.resolve(library.fileName).normalize();
        if (!target.getParent().equals(libraryDirectory)) {
            throw new IOException("Invalid JDBC library target: " + target);
        }
        if (Files.isSymbolicLink(target)) {
            throw new IOException("Refusing to replace symbolic-link JDBC library: " + target);
        }
        if (Files.isRegularFile(target) && matchesLibrary(target, library)) {
            return target;
        }

        platform.info("Downloading missing JDBC support library " + library.fileName);
        Path temporary = libraryDirectory.resolve(library.fileName + ".tmp-" + UUID.randomUUID()).normalize();
        if (!temporary.getParent().equals(libraryDirectory)) {
            throw new IOException("Invalid temporary JDBC library target: " + temporary);
        }
        boolean moved = false;
        try {
            downloadLibrary(library, temporary);
            try {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
            moved = true;
            return target;
        } finally {
            if (!moved) {
                Files.deleteIfExists(temporary);
            }
        }
    }

    private void downloadLibrary(Library library, Path temporary) throws IOException {
        HttpURLConnection connection = null;
        try {
            URL url = URI.create(library.url).toURL();
            if (!"https".equalsIgnoreCase(url.getProtocol())
                    || !"repo.maven.apache.org".equalsIgnoreCase(url.getHost())) {
                throw new IOException("Unexpected JDBC library source: " + library.url);
            }
            connection = (HttpURLConnection) url.openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(30000);
            connection.setRequestProperty("User-Agent", "ShitBot-JDBC-Resolver");
            int status = connection.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) {
                throw new IOException("JDBC library download returned HTTP " + status);
            }
            long contentLength = connection.getContentLengthLong();
            if (contentLength >= 0L && contentLength != library.size) {
                throw new IOException("Unexpected Content-Length for " + library.fileName
                        + ": " + contentLength + " (expected " + library.size + ')');
            }

            MessageDigest digest = sha256Digest();
            long received = 0L;
            byte[] buffer = new byte[16384];
            try (InputStream input = connection.getInputStream();
                 OutputStream output = Files.newOutputStream(temporary,
                         StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    if (read == 0) {
                        continue;
                    }
                    received += read;
                    if (received > library.size) {
                        throw new IOException("JDBC library exceeds pinned size: " + library.fileName);
                    }
                    digest.update(buffer, 0, read);
                    output.write(buffer, 0, read);
                }
            }
            String actualHash = HashUtil.toHex(digest.digest());
            if (received != library.size || !library.sha256.equals(actualHash)) {
                throw new IOException("JDBC library verification failed for " + library.fileName
                        + " (size=" + received + ", sha256=" + actualHash + ')');
            }
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private boolean matchesLibrary(Path path, Library library) throws IOException {
        if (Files.size(path) != library.size) {
            return false;
        }
        MessageDigest digest = sha256Digest();
        byte[] buffer = new byte[16384];
        try (InputStream input = Files.newInputStream(path)) {
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) {
                    digest.update(buffer, 0, read);
                }
            }
        }
        return library.sha256.equals(HashUtil.toHex(digest.digest()));
    }

    private MessageDigest sha256Digest() throws IOException {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IOException("SHA-256 is unavailable", exception);
        }
    }

    private void logDriver(String type, Driver driver, String source) {
        platform.info(type + " JDBC " + driver.getMajorVersion() + '.' + driver.getMinorVersion()
                + " selected from " + source + " (" + driver.getClass().getName() + ')');
    }

    private static String redactJdbcUrl(String jdbcUrl) {
        int question = jdbcUrl == null ? -1 : jdbcUrl.indexOf('?');
        return question < 0 ? String.valueOf(jdbcUrl) : jdbcUrl.substring(0, question) + "?…";
    }

    private static String queryValue(String jdbcUrl, String name) {
        for (String parameter : queryParameters(jdbcUrl)) {
            int equals = parameter.indexOf('=');
            String key = equals < 0 ? parameter : parameter.substring(0, equals);
            if (name.equalsIgnoreCase(key.trim())) {
                return equals < 0 ? "" : parameter.substring(equals + 1);
            }
        }
        return null;
    }

    private static List<String> queryParameters(String jdbcUrl) {
        List<String> parameters = new ArrayList<String>();
        int question = jdbcUrl == null ? -1 : jdbcUrl.indexOf('?');
        if (question < 0 || question + 1 >= jdbcUrl.length()) {
            return parameters;
        }
        String[] split = jdbcUrl.substring(question + 1).split("&");
        for (String value : split) {
            if (!value.trim().isEmpty()) {
                parameters.add(value);
            }
        }
        return parameters;
    }

    private static void removeParameter(List<String> parameters, String name) {
        for (int index = parameters.size() - 1; index >= 0; index--) {
            String parameter = parameters.get(index);
            int equals = parameter.indexOf('=');
            String key = equals < 0 ? parameter : parameter.substring(0, equals);
            if (name.equalsIgnoreCase(key.trim())) {
                parameters.remove(index);
            }
        }
    }

    private static void setParameter(List<String> parameters, String name, String value) {
        removeParameter(parameters, name);
        parameters.add(name + '=' + value);
    }

    private static String join(List<String> values, String separator) {
        StringBuilder builder = new StringBuilder();
        for (String value : values) {
            if (builder.length() > 0) {
                builder.append(separator);
            }
            builder.append(value);
        }
        return builder.toString();
    }

    @Override
    public synchronized void close() {
        sqliteDriver = null;
        mysqlDriver = null;
        mysqlApi = null;
        closeQuietly(sqliteClassLoader);
        closeQuietly(mysqlClassLoader);
        sqliteClassLoader = null;
        mysqlClassLoader = null;
    }

    private static void closeQuietly(URLClassLoader classLoader) {
        if (classLoader == null) {
            return;
        }
        try {
            classLoader.close();
        } catch (IOException ignored) {
            // Best-effort release of downloaded JAR handles.
        }
    }

    private enum MysqlApi {
        MODERN,
        LEGACY
    }

    private static final class ResolvedMysql {
        private final Driver driver;
        private final String jdbcUrl;

        private ResolvedMysql(Driver driver, String jdbcUrl) {
            this.driver = driver;
            this.jdbcUrl = jdbcUrl;
        }
    }

    private static final class Library {
        private final String fileName;
        private final String url;
        private final long size;
        private final String sha256;

        private Library(String fileName, String url, long size, String sha256) {
            this.fileName = fileName;
            this.url = url;
            this.size = size;
            this.sha256 = sha256;
        }
    }

    /** Loads only the fallback driver's own packages child-first. */
    private static final class DriverClassLoader extends URLClassLoader {
        private final String[] childFirstPrefixes;

        private DriverClassLoader(URL[] urls, ClassLoader parent, String[] childFirstPrefixes) {
            super(urls, parent);
            this.childFirstPrefixes = childFirstPrefixes;
        }

        @Override
        protected synchronized Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            for (String prefix : childFirstPrefixes) {
                if (name.startsWith(prefix)) {
                    Class<?> loaded = findLoadedClass(name);
                    if (loaded == null) {
                        try {
                            loaded = findClass(name);
                        } catch (ClassNotFoundException ignored) {
                            // Fall through to the parent for optional/support classes.
                        }
                    }
                    if (loaded != null) {
                        if (resolve) {
                            resolveClass(loaded);
                        }
                        return loaded;
                    }
                    break;
                }
            }
            return super.loadClass(name, resolve);
        }
    }
}
