package haaa.shitbot.core.database;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import haaa.shitbot.core.config.Settings;
import haaa.shitbot.core.platform.PlatformBridge;
import haaa.shitbot.core.util.NamedThreadFactory;

import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Owns the JDBC pool and applies platform-independent schema migrations.
 * Every server type uses the same tables and column types, so a database can be
 * moved from Spigot to BungeeCord or Velocity without conversion.
 */
public final class DatabaseManager implements AutoCloseable {
    public static final int SCHEMA_VERSION = 2;

    private static final String BINDINGS_TABLE = "shitbot_bindings";
    private static final String CODES_TABLE = "shitbot_bind_codes";
    private static final String LEGACY_PLAYER_KEY_COLUMN = "player_name_key";

    private final Settings.Database settings;
    private final PlatformBridge platform;
    private final ExecutorService executor;
    private final String workerThreadPrefix;
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile HikariDataSource dataSource;

    public DatabaseManager(Settings.Database settings, PlatformBridge platform) {
        this.settings = settings;
        this.platform = platform;
        this.workerThreadPrefix = "shitbot-db-" + Integer.toHexString(System.identityHashCode(this));
        this.executor = Executors.newFixedThreadPool(
                settings.getAsyncThreads(),
                new NamedThreadFactory(workerThreadPrefix, true));
    }

    public CompletableFuture<Void> initializeAsync() {
        return CompletableFuture.runAsync(new Runnable() {
            @Override
            public void run() {
                initialize();
            }
        }, executor);
    }

    private void initialize() {
        if (closed.get()) {
            throw new IllegalStateException("Database manager is already closed");
        }
        try {
            Files.createDirectories(platform.getDataDirectory());
            loadDriver();
            HikariConfig config = createHikariConfig();
            HikariDataSource newDataSource = new HikariDataSource(config);
            try (Connection connection = newDataSource.getConnection()) {
                configureDatabase(connection);
                applyMigrations(connection);
            } catch (Throwable throwable) {
                newDataSource.close();
                throw throwable;
            }
            dataSource = newDataSource;
            platform.info("Database initialized: " + settings.getType().name().toLowerCase(Locale.ROOT)
                    + ", schema v" + SCHEMA_VERSION);
        } catch (Throwable throwable) {
            throw new CompletionException("Failed to initialize database", throwable);
        }
    }

    private void loadDriver() throws ClassNotFoundException {
        if (settings.getType() == Settings.Database.Type.SQLITE) {
            Class.forName("org.sqlite.JDBC");
        } else {
            Class.forName("com.mysql.cj.jdbc.Driver");
        }
    }

    private HikariConfig createHikariConfig() {
        HikariConfig config = new HikariConfig();
        config.setPoolName("ShitBot-" + settings.getType().name());
        config.setJdbcUrl(settings.buildJdbcUrl(platform.getDataDirectory()));
        if (settings.getType() == Settings.Database.Type.MYSQL) {
            config.setUsername(settings.getMysqlUsername());
            config.setPassword(settings.getMysqlPassword());
            config.setMaximumPoolSize(settings.getMaximumPoolSize());
            config.setMinimumIdle(settings.getMinimumIdle());
            config.setIdleTimeout(settings.getIdleTimeoutMs());
            config.setMaxLifetime(settings.getMaximumLifetimeMs());
            if (settings.getKeepaliveTimeMs() > 0L) {
                config.setKeepaliveTime(settings.getKeepaliveTimeMs());
            }
            config.addDataSourceProperty("cachePrepStmts", "true");
            config.addDataSourceProperty("prepStmtCacheSize", "250");
            config.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
            config.addDataSourceProperty("useServerPrepStmts", "true");
            config.addDataSourceProperty("rewriteBatchedStatements", "true");
        } else {
            config.setMaximumPoolSize(1);
            config.setMinimumIdle(1);
            config.setMaxLifetime(0L);
            config.setIdleTimeout(0L);
            config.setConnectionInitSql("PRAGMA foreign_keys=ON");
        }
        config.setConnectionTimeout(settings.getConnectionTimeoutMs());
        config.setValidationTimeout(settings.getValidationTimeoutMs());
        config.setAutoCommit(true);
        config.setInitializationFailTimeout(settings.getConnectionTimeoutMs());
        return config;
    }

    private void configureDatabase(Connection connection) throws SQLException {
        configureConnection(connection);
        if (settings.getType() == Settings.Database.Type.SQLITE) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA journal_mode=WAL");
                statement.execute("PRAGMA synchronous=NORMAL");
            }
        }
    }

    private void configureConnection(Connection connection) throws SQLException {
        if (settings.getType() == Settings.Database.Type.SQLITE) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA foreign_keys=ON");
                statement.execute("PRAGMA busy_timeout=5000");
            }
        }
    }

    private void applyMigrations(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS shitbot_schema ("
                    + "schema_key VARCHAR(64) NOT NULL PRIMARY KEY, "
                    + "version INTEGER NOT NULL, "
                    + "applied_at BIGINT NOT NULL"
                    + ")");
        }

        int currentVersion = readSchemaVersion(connection);
        if (currentVersion > SCHEMA_VERSION) {
            throw new SQLException("Database schema v" + currentVersion
                    + " is newer than this plugin supports (v" + SCHEMA_VERSION + ")");
        }

        if (currentVersion == 0) {
            boolean legacySchema = columnExists(connection, BINDINGS_TABLE, LEGACY_PLAYER_KEY_COLUMN)
                    || columnExists(connection, CODES_TABLE, LEGACY_PLAYER_KEY_COLUMN);
            if (legacySchema) {
                migrateToVersion2(connection);
            } else {
                createVersion2Schema(connection);
            }
            writeSchemaVersion(connection, 2);
            return;
        }

        if (currentVersion < 2) {
            migrateToVersion2(connection);
            writeSchemaVersion(connection, 2);
        }
    }

    private int readSchemaVersion(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT version FROM shitbot_schema WHERE schema_key = ?")) {
            statement.setString(1, "main");
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getInt(1) : 0;
            }
        }
    }

    private void writeSchemaVersion(Connection connection, int version) throws SQLException {
        long now = System.currentTimeMillis();
        if (settings.getType() == Settings.Database.Type.SQLITE) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO shitbot_schema(schema_key, version, applied_at) VALUES(?, ?, ?) "
                            + "ON CONFLICT(schema_key) DO UPDATE SET version=excluded.version, applied_at=excluded.applied_at")) {
                statement.setString(1, "main");
                statement.setInt(2, version);
                statement.setLong(3, now);
                statement.executeUpdate();
            }
        } else {
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO shitbot_schema(schema_key, version, applied_at) VALUES(?, ?, ?) "
                            + "ON DUPLICATE KEY UPDATE version=VALUES(version), applied_at=VALUES(applied_at)")) {
                statement.setString(1, "main");
                statement.setInt(2, version);
                statement.setLong(3, now);
                statement.executeUpdate();
            }
        }
    }

    private void createVersion2Schema(Connection connection) throws SQLException {
        String idColumn = settings.getType() == Settings.Database.Type.SQLITE
                ? "INTEGER PRIMARY KEY AUTOINCREMENT"
                : "BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY";
        String playerNameColumn = settings.getType() == Settings.Database.Type.MYSQL
                ? "VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL"
                : "VARCHAR(16) NOT NULL";
        String tableOptions = settings.getType() == Settings.Database.Type.MYSQL
                ? " ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci"
                : "";

        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS " + BINDINGS_TABLE + " ("
                    + "id " + idColumn + ", "
                    + "player_name " + playerNameColumn + ", "
                    + "player_uuid VARCHAR(36) NULL, "
                    + "qq_id VARCHAR(20) NOT NULL, "
                    + "created_at BIGINT NOT NULL, "
                    + "updated_at BIGINT NOT NULL, "
                    + "CONSTRAINT uq_shitbot_bindings_player UNIQUE(player_name), "
                    + "CONSTRAINT uq_shitbot_bindings_qq UNIQUE(qq_id)"
                    + ")" + tableOptions);

            statement.execute("CREATE TABLE IF NOT EXISTS " + CODES_TABLE + " ("
                    + "player_name " + playerNameColumn + " PRIMARY KEY, "
                    + "code_hash VARCHAR(64) NOT NULL, "
                    + "code_salt VARCHAR(64) NOT NULL, "
                    + "expires_at BIGINT NOT NULL, "
                    + "attempts INTEGER NOT NULL, "
                    + "created_at BIGINT NOT NULL, "
                    + "updated_at BIGINT NOT NULL"
                    + ")" + tableOptions);
        }

        enforceCaseSensitivePlayerColumns(connection);
        ensureCurrentIndexes(connection);
    }

    /**
     * Removes the lower-cased player-name key introduced by schema v1.
     * The exact player name becomes the key and MySQL uses a binary collation,
     * so differently-cased offline-mode names remain completely independent.
     */
    private void migrateToVersion2(Connection connection) throws SQLException {
        boolean bindingsHaveLegacyKey = columnExists(connection, BINDINGS_TABLE, LEGACY_PLAYER_KEY_COLUMN);
        boolean codesHaveLegacyKey = columnExists(connection, CODES_TABLE, LEGACY_PLAYER_KEY_COLUMN);

        if (!bindingsHaveLegacyKey && !codesHaveLegacyKey) {
            cleanupMigrationArtifacts(connection);
            enforceCaseSensitivePlayerColumns(connection);
            ensureCurrentIndexes(connection);
            return;
        }

        if (settings.getType() == Settings.Database.Type.SQLITE) {
            migrateSqliteToVersion2(connection);
        } else {
            migrateMysqlToVersion2(connection);
        }
        enforceCaseSensitivePlayerColumns(connection);
        ensureCurrentIndexes(connection);
    }

    private void migrateSqliteToVersion2(Connection connection) throws SQLException {
        boolean previousAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try (Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS shitbot_bindings_v2_tmp");
            statement.execute("DROP TABLE IF EXISTS shitbot_bind_codes_v2_tmp");

            statement.execute("CREATE TABLE shitbot_bindings_v2_tmp ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT, "
                    + "player_name VARCHAR(16) NOT NULL, "
                    + "player_uuid VARCHAR(36) NULL, "
                    + "qq_id VARCHAR(20) NOT NULL, "
                    + "created_at BIGINT NOT NULL, "
                    + "updated_at BIGINT NOT NULL, "
                    + "CONSTRAINT uq_shitbot_bindings_player UNIQUE(player_name), "
                    + "CONSTRAINT uq_shitbot_bindings_qq UNIQUE(qq_id)"
                    + ")");
            statement.execute("INSERT INTO shitbot_bindings_v2_tmp("
                    + "id, player_name, player_uuid, qq_id, created_at, updated_at) "
                    + "SELECT id, player_name, player_uuid, qq_id, created_at, updated_at FROM " + BINDINGS_TABLE);

            statement.execute("CREATE TABLE shitbot_bind_codes_v2_tmp ("
                    + "player_name VARCHAR(16) NOT NULL PRIMARY KEY, "
                    + "code_hash VARCHAR(64) NOT NULL, "
                    + "code_salt VARCHAR(64) NOT NULL, "
                    + "expires_at BIGINT NOT NULL, "
                    + "attempts INTEGER NOT NULL, "
                    + "created_at BIGINT NOT NULL, "
                    + "updated_at BIGINT NOT NULL"
                    + ")");
            statement.execute("INSERT INTO shitbot_bind_codes_v2_tmp("
                    + "player_name, code_hash, code_salt, expires_at, attempts, created_at, updated_at) "
                    + "SELECT player_name, code_hash, code_salt, expires_at, attempts, created_at, updated_at "
                    + "FROM " + CODES_TABLE);

            statement.execute("DROP TABLE " + BINDINGS_TABLE);
            statement.execute("ALTER TABLE shitbot_bindings_v2_tmp RENAME TO " + BINDINGS_TABLE);
            statement.execute("DROP TABLE " + CODES_TABLE);
            statement.execute("ALTER TABLE shitbot_bind_codes_v2_tmp RENAME TO " + CODES_TABLE);
            connection.commit();
        } catch (SQLException exception) {
            try {
                connection.rollback();
            } catch (SQLException rollbackError) {
                exception.addSuppressed(rollbackError);
            }
            throw exception;
        } finally {
            connection.setAutoCommit(previousAutoCommit);
        }
    }

    private void migrateMysqlToVersion2(Connection connection) throws SQLException {
        final String bindingsTemporary = "shitbot_bindings_v2_tmp";
        final String codesTemporary = "shitbot_bind_codes_v2_tmp";
        final String bindingsBackup = "shitbot_bindings_v1_backup";
        final String codesBackup = "shitbot_bind_codes_v1_backup";
        final String tableOptions = " ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci";
        final String playerNameColumn = "player_name VARCHAR(16) CHARACTER SET utf8mb4 "
                + "COLLATE utf8mb4_bin NOT NULL";

        try (Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS " + bindingsTemporary + ", " + codesTemporary);
            statement.execute("DROP TABLE IF EXISTS " + bindingsBackup + ", " + codesBackup);

            statement.execute("CREATE TABLE " + bindingsTemporary + " ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, "
                    + playerNameColumn + ", "
                    + "player_uuid VARCHAR(36) NULL, "
                    + "qq_id VARCHAR(20) NOT NULL, "
                    + "created_at BIGINT NOT NULL, "
                    + "updated_at BIGINT NOT NULL, "
                    + "CONSTRAINT uq_shitbot_bindings_player UNIQUE(player_name), "
                    + "CONSTRAINT uq_shitbot_bindings_qq UNIQUE(qq_id)"
                    + ")" + tableOptions);
            statement.execute("INSERT INTO " + bindingsTemporary + "("
                    + "id, player_name, player_uuid, qq_id, created_at, updated_at) "
                    + "SELECT id, player_name, player_uuid, qq_id, created_at, updated_at FROM " + BINDINGS_TABLE);

            statement.execute("CREATE TABLE " + codesTemporary + " ("
                    + playerNameColumn + " PRIMARY KEY, "
                    + "code_hash VARCHAR(64) NOT NULL, "
                    + "code_salt VARCHAR(64) NOT NULL, "
                    + "expires_at BIGINT NOT NULL, "
                    + "attempts INTEGER NOT NULL, "
                    + "created_at BIGINT NOT NULL, "
                    + "updated_at BIGINT NOT NULL"
                    + ")" + tableOptions);
            statement.execute("INSERT INTO " + codesTemporary + "("
                    + "player_name, code_hash, code_salt, expires_at, attempts, created_at, updated_at) "
                    + "SELECT player_name, code_hash, code_salt, expires_at, attempts, created_at, updated_at "
                    + "FROM " + CODES_TABLE);

            statement.execute("RENAME TABLE "
                    + BINDINGS_TABLE + " TO " + bindingsBackup + ", "
                    + bindingsTemporary + " TO " + BINDINGS_TABLE + ", "
                    + CODES_TABLE + " TO " + codesBackup + ", "
                    + codesTemporary + " TO " + CODES_TABLE);
            statement.execute("DROP TABLE " + bindingsBackup + ", " + codesBackup);
        }
    }

    private void cleanupMigrationArtifacts(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            if (settings.getType() == Settings.Database.Type.MYSQL) {
                statement.execute("DROP TABLE IF EXISTS shitbot_bindings_v2_tmp, shitbot_bind_codes_v2_tmp, "
                        + "shitbot_bindings_v1_backup, shitbot_bind_codes_v1_backup");
            } else {
                statement.execute("DROP TABLE IF EXISTS shitbot_bindings_v2_tmp");
                statement.execute("DROP TABLE IF EXISTS shitbot_bind_codes_v2_tmp");
            }
        }
    }

    private void enforceCaseSensitivePlayerColumns(Connection connection) throws SQLException {
        if (settings.getType() != Settings.Database.Type.MYSQL) {
            return;
        }
        try (Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE " + BINDINGS_TABLE
                    + " MODIFY player_name VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL");
            statement.execute("ALTER TABLE " + CODES_TABLE
                    + " MODIFY player_name VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL");
        }
    }

    private void ensureCurrentIndexes(Connection connection) throws SQLException {
        ensureIndex(connection, BINDINGS_TABLE, "idx_shitbot_bindings_uuid", "player_uuid");
        ensureIndex(connection, CODES_TABLE, "idx_shitbot_bind_codes_expires", "expires_at");
    }

    private boolean columnExists(Connection connection, String table, String column) throws SQLException {
        DatabaseMetaData metaData = connection.getMetaData();
        String[] tableCandidates = new String[]{table, table.toUpperCase(Locale.ROOT), table.toLowerCase(Locale.ROOT)};
        for (String tableCandidate : tableCandidates) {
            try (ResultSet resultSet = metaData.getColumns(connection.getCatalog(), null, tableCandidate, null)) {
                while (resultSet.next()) {
                    String existing = resultSet.getString("COLUMN_NAME");
                    if (existing != null && column.equalsIgnoreCase(existing)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private void ensureIndex(Connection connection, String table, String index, String column) throws SQLException {
        if (indexExists(connection, table, index)) {
            return;
        }
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE INDEX " + index + " ON " + table + '(' + column + ')');
        }
    }

    private boolean indexExists(Connection connection, String table, String index) throws SQLException {
        DatabaseMetaData metaData = connection.getMetaData();
        try (ResultSet resultSet = metaData.getIndexInfo(connection.getCatalog(), null, table, false, false)) {
            while (resultSet.next()) {
                String existing = resultSet.getString("INDEX_NAME");
                if (existing != null && index.equalsIgnoreCase(existing)) {
                    return true;
                }
            }
        }
        // Some MySQL configurations expose table names using a different case.
        try (ResultSet resultSet = metaData.getIndexInfo(connection.getCatalog(), null,
                table.toUpperCase(Locale.ROOT), false, false)) {
            while (resultSet.next()) {
                String existing = resultSet.getString("INDEX_NAME");
                if (existing != null && index.equalsIgnoreCase(existing)) {
                    return true;
                }
            }
        }
        return false;
    }

    public <T> CompletableFuture<T> supplyAsync(final SqlFunction<T> function) {
        if (closed.get()) {
            CompletableFuture<T> failed = new CompletableFuture<T>();
            failed.completeExceptionally(new IllegalStateException("Database manager is closed"));
            return failed;
        }
        return CompletableFuture.supplyAsync(new java.util.function.Supplier<T>() {
            @Override
            public T get() {
                HikariDataSource current = dataSource;
                if (current == null) {
                    throw new CompletionException(new IllegalStateException("Database is not initialized"));
                }
                try (Connection connection = current.getConnection()) {
                    configureConnection(connection);
                    return function.apply(connection);
                } catch (Throwable throwable) {
                    throw new CompletionException(throwable);
                }
            }
        }, executor);
    }

    public <T> CompletableFuture<T> transactionAsync(final SqlFunction<T> function) {
        return supplyAsync(new SqlFunction<T>() {
            @Override
            public T apply(Connection connection) throws Exception {
                boolean previousAutoCommit = connection.getAutoCommit();
                connection.setAutoCommit(false);
                try {
                    T value = function.apply(connection);
                    connection.commit();
                    return value;
                } catch (Throwable throwable) {
                    try {
                        connection.rollback();
                    } catch (SQLException rollbackError) {
                        throwable.addSuppressed(rollbackError);
                    }
                    if (throwable instanceof Exception) {
                        throw (Exception) throwable;
                    }
                    throw new RuntimeException(throwable);
                } finally {
                    connection.setAutoCommit(previousAutoCommit);
                }
            }
        });
    }

    public boolean isReady() {
        HikariDataSource current = dataSource;
        return current != null && !current.isClosed() && !closed.get();
    }

    public Settings.Database.Type getType() {
        return settings.getType();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        executor.shutdown();
        boolean calledFromOwnWorker = Thread.currentThread().getName().startsWith(workerThreadPrefix + '-');
        if (!calledFromOwnWorker) {
            try {
                if (!executor.awaitTermination(5L, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                executor.shutdownNow();
            }
        } else {
            executor.shutdownNow();
        }

        HikariDataSource current = dataSource;
        dataSource = null;
        if (current != null) {
            current.close();
        }
    }

    public interface SqlFunction<T> {
        T apply(Connection connection) throws Exception;
    }
}
