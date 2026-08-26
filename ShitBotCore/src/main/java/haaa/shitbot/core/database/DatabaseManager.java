package haaa.shitbot.core.database;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import haaa.shitbot.core.config.Settings;
import haaa.shitbot.core.platform.PlatformBridge;
import haaa.shitbot.core.util.NamedThreadFactory;
import haaa.shitbot.core.util.NetworkUtil;

import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Owns the JDBC pool and applies platform-independent schema migrations.
 * Every server type uses the same tables and column types, so a database can be
 * moved from Spigot to BungeeCord or Velocity without conversion.
 */
public final class DatabaseManager implements AutoCloseable {
    public static final int SCHEMA_VERSION = 6;

    private static final int MYSQL_MIGRATION_LOCK_TIMEOUT_SECONDS = 30;

    private static final String BINDINGS_TABLE = "shitbot_bindings";
    private static final String CODES_TABLE = "shitbot_bind_codes";
    private static final String BIND_ATTEMPTS_TABLE = "shitbot_bind_attempts";
    private static final String INVENTORY_SNAPSHOTS_TABLE = "shitbot_inventory_snapshots";
    private static final String LEGACY_PLAYER_KEY_COLUMN = "player_name_key";

    private final Settings.Database settings;
    private final PlatformBridge platform;
    private final ThreadPoolExecutor executor;
    private final String workerThreadPrefix;
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile HikariDataSource mysqlDataSource;
    private volatile Connection sqliteConnection;

    public DatabaseManager(Settings.Database settings, PlatformBridge platform) {
        this.settings = settings;
        this.platform = platform;
        this.workerThreadPrefix = "shitbot-db-" + Integer.toHexString(System.identityHashCode(this));
        int threads = settings.getAsyncThreads();
        this.executor = new ThreadPoolExecutor(
                threads,
                threads,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<Runnable>(settings.getMaximumQueuedTasks()),
                new NamedThreadFactory(workerThreadPrefix, true),
                new ThreadPoolExecutor.AbortPolicy());
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
            validateRemoteMysqlSecurity();
            loadDriver();
            if (settings.getType() == Settings.Database.Type.SQLITE) {
                initializeSqlite();
            } else {
                initializeMysql();
            }
            platform.info("Database initialized: " + settings.getType().name().toLowerCase(Locale.ROOT)
                    + ", schema v" + SCHEMA_VERSION);
        } catch (Throwable throwable) {
            throw new CompletionException("Failed to initialize database", throwable);
        }
    }

    private void validateRemoteMysqlSecurity() {
        if (settings.getType() != Settings.Database.Type.MYSQL
                || NetworkUtil.isLoopbackHost(settings.getMysqlHost())) {
            return;
        }
        String parameters = settings.getMysqlParameters().toLowerCase(Locale.ROOT);
        boolean tlsRequired = parameters.contains("requiressl=true")
                || parameters.contains("sslmode=required")
                || parameters.contains("sslmode=verify_ca")
                || parameters.contains("sslmode=verify_identity")
                || (parameters.contains("usessl=true")
                && parameters.contains("verifyservercertificate=true"));
        if (tlsRequired) {
            return;
        }
        String endpoint = settings.getMysqlHost() + ":" + settings.getMysqlPort();
        if (!settings.isAllowInsecureRemoteMysql()) {
            throw new IllegalStateException("Remote MySQL must require TLS: " + endpoint
                    + ". Configure sslMode=VERIFY_IDENTITY (recommended), or explicitly set "
                    + "database.mysql.allow-insecure-remote-mysql=true to accept plaintext risk");
        }
        platform.warn("Remote MySQL TLS protection is explicitly disabled; credentials and data may be exposed: "
                + endpoint);
    }

    private void loadDriver() throws ClassNotFoundException {
        if (settings.getType() == Settings.Database.Type.SQLITE) {
            Class.forName("org.sqlite.JDBC");
        } else {
            Class.forName("com.mysql.cj.jdbc.Driver");
        }
    }

    /**
     * SQLite deliberately does not use HikariCP. Older CraftBukkit/Spigot
     * distributions expose a legacy org.sqlite driver through the parent
     * class loader. Those drivers can open and use a database, but they do not
     * implement JDBC 4 methods such as Connection#isValid(int), which Hikari
     * calls while creating a pool. A single persistent connection is safe here
     * because every SQLite operation is serialized by the one database worker.
     */
    private void initializeSqlite() throws SQLException {
        Connection connection = DriverManager.getConnection(
                settings.buildJdbcUrl(platform.getDataDirectory()));
        boolean success = false;
        try {
            configureDatabase(connection);
            applyMigrations(connection);
            sqliteConnection = connection;
            platform.info("SQLite backend uses one serialized direct connection; HikariCP is disabled for SQLite");
            success = true;
        } finally {
            if (!success) {
                closeQuietly(connection);
            }
        }
    }

    private void initializeMysql() throws SQLException {
        HikariConfig config = createMysqlHikariConfig();
        HikariDataSource newDataSource = new HikariDataSource(config);
        try (Connection connection = newDataSource.getConnection()) {
            configureDatabase(connection);
            applyMigrations(connection);
        } catch (Throwable throwable) {
            newDataSource.close();
            if (throwable instanceof SQLException) {
                throw (SQLException) throwable;
            }
            if (throwable instanceof RuntimeException) {
                throw (RuntimeException) throwable;
            }
            if (throwable instanceof Error) {
                throw (Error) throwable;
            }
            throw new SQLException("Failed to initialize MySQL", throwable);
        }
        mysqlDataSource = newDataSource;
    }

    private HikariConfig createMysqlHikariConfig() {
        HikariConfig config = new HikariConfig();
        config.setPoolName("ShitBot-" + settings.getType().name());
        config.setJdbcUrl(settings.buildJdbcUrl(platform.getDataDirectory()));
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
        config.addDataSourceProperty("connectTimeout", String.valueOf(settings.getMysqlConnectTimeoutMs()));
        config.addDataSourceProperty("socketTimeout", String.valueOf(settings.getMysqlSocketTimeoutMs()));
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
        if (settings.getType() != Settings.Database.Type.MYSQL) {
            applyMigrationsLocked(connection);
            return;
        }

        String lockName = migrationLockName(connection);
        boolean acquired = false;
        SQLException migrationFailure = null;
        try {
            acquireMysqlMigrationLock(connection, lockName);
            acquired = true;
            // The version is intentionally read only after acquiring the cross-JVM lock.
            applyMigrationsLocked(connection);
        } catch (SQLException exception) {
            migrationFailure = exception;
            throw exception;
        } finally {
            if (acquired) {
                try {
                    releaseMysqlMigrationLock(connection, lockName);
                } catch (SQLException releaseFailure) {
                    if (migrationFailure != null) {
                        migrationFailure.addSuppressed(releaseFailure);
                    } else {
                        throw releaseFailure;
                    }
                }
            }
        }
    }

    private void applyMigrationsLocked(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS shitbot_schema ("
                    + "schema_key VARCHAR(64) NOT NULL PRIMARY KEY, "
                    + "version INTEGER NOT NULL, applied_at BIGINT NOT NULL)");
        }
        int currentVersion = readSchemaVersion(connection);
        if (currentVersion > SCHEMA_VERSION) {
            throw new SQLException("Database schema v" + currentVersion
                    + " is newer than this plugin supports (v" + SCHEMA_VERSION + ")");
        }
        if (currentVersion == 0) {
            boolean bindingsExist = tableExists(connection, BINDINGS_TABLE);
            boolean codesExist = tableExists(connection, CODES_TABLE);
            if (!bindingsExist && !codesExist) {
                createVersion6Schema(connection);
                writeSchemaVersion(connection, 6);
                return;
            }
            boolean legacySchema = columnExists(connection, BINDINGS_TABLE, LEGACY_PLAYER_KEY_COLUMN)
                    || columnExists(connection, CODES_TABLE, LEGACY_PLAYER_KEY_COLUMN);
            if (legacySchema) {
                migrateToVersion2(connection);
            } else {
                createVersion2Schema(connection);
            }
            currentVersion = 2;
            writeSchemaVersion(connection, currentVersion);
        }
        if (currentVersion < 2) {
            migrateToVersion2(connection);
            currentVersion = 2;
            writeSchemaVersion(connection, currentVersion);
        }
        if (currentVersion < 3) {
            migrateToVersion3(connection);
            currentVersion = 3;
            writeSchemaVersion(connection, currentVersion);
        }
        if (currentVersion < 4) {
            migrateToVersion4(connection);
            currentVersion = 4;
            writeSchemaVersion(connection, currentVersion);
        }
        if (currentVersion < 5) {
            migrateToVersion5(connection);
            currentVersion = 5;
            writeSchemaVersion(connection, currentVersion);
        }
        if (currentVersion < 6) {
            migrateToVersion6(connection);
            currentVersion = 6;
            writeSchemaVersion(connection, currentVersion);
        }
        enforceCaseSensitivePlayerColumns(connection);
        ensureCurrentIndexes(connection);
    }

    private String migrationLockName(Connection connection) throws SQLException {
        String databaseName = connection.getCatalog();
        if (databaseName == null || databaseName.trim().isEmpty()) {
            databaseName = settings.getMysqlDatabase();
        }
        String name = "shitbot_schema_migration:" + databaseName.trim().toLowerCase(Locale.ROOT);
        return name.length() <= 64 ? name : name.substring(0, 64);
    }

    private void acquireMysqlMigrationLock(Connection connection, String lockName) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT GET_LOCK(?, ?)")) {
            statement.setString(1, lockName);
            statement.setInt(2, MYSQL_MIGRATION_LOCK_TIMEOUT_SECONDS);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next() || resultSet.getInt(1) != 1 || resultSet.wasNull()) {
                    throw new SQLException("Timed out waiting for MySQL schema migration lock " + lockName);
                }
            }
        }
    }

    private void releaseMysqlMigrationLock(Connection connection, String lockName) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT RELEASE_LOCK(?)")) {
            statement.setString(1, lockName);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next() || resultSet.getInt(1) != 1 || resultSet.wasNull()) {
                    throw new SQLException("MySQL schema migration lock was not released: " + lockName);
                }
            }
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
            int updated;
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE shitbot_schema SET version=?, applied_at=? WHERE schema_key=?")) {
                statement.setInt(1, version);
                statement.setLong(2, now);
                statement.setString(3, "main");
                updated = statement.executeUpdate();
            }
            if (updated == 0) {
                try (PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO shitbot_schema(schema_key, version, applied_at) VALUES(?, ?, ?)")) {
                    statement.setString(1, "main");
                    statement.setInt(2, version);
                    statement.setLong(3, now);
                    statement.executeUpdate();
                }
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

    /** Creates the current schema. QQ numbers are indexed but intentionally not unique. */
    private void createVersion6Schema(Connection connection) throws SQLException {
        createVersion5Schema(connection);
        ensureBindCodeCooldownColumn(connection);
    }

    private void createVersion5Schema(Connection connection) throws SQLException {
        createVersion4Schema(connection);
        createBindAttemptTable(connection);
        enforceCaseSensitivePlayerColumns(connection);
        ensureCurrentIndexes(connection);
    }

    private void createVersion4Schema(Connection connection) throws SQLException {
        createVersion3Schema(connection);
        createInventorySnapshotTable(connection);
        enforceCaseSensitivePlayerColumns(connection);
        ensureCurrentIndexes(connection);
    }

    /** Creates schema v3 binding tables. */
    private void createVersion3Schema(Connection connection) throws SQLException {
        String idColumn = settings.getType() == Settings.Database.Type.SQLITE
                ? "INTEGER PRIMARY KEY AUTOINCREMENT" : "BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY";
        String playerNameColumn = settings.getType() == Settings.Database.Type.MYSQL
                ? "VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL" : "VARCHAR(16) NOT NULL";
        String tableOptions = settings.getType() == Settings.Database.Type.MYSQL
                ? " ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci" : "";
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS " + BINDINGS_TABLE + " ("
                    + "id " + idColumn + ", player_name " + playerNameColumn + ", "
                    + "player_uuid VARCHAR(36) NULL, qq_id VARCHAR(20) NOT NULL, "
                    + "created_at BIGINT NOT NULL, updated_at BIGINT NOT NULL, "
                    + "CONSTRAINT uq_shitbot_bindings_player UNIQUE(player_name))" + tableOptions);
            statement.execute("CREATE TABLE IF NOT EXISTS " + CODES_TABLE + " ("
                    + "player_name " + playerNameColumn + " PRIMARY KEY, code_hash VARCHAR(64) NOT NULL, "
                    + "code_salt VARCHAR(64) NOT NULL, expires_at BIGINT NOT NULL, attempts INTEGER NOT NULL, "
                    + "created_at BIGINT NOT NULL, updated_at BIGINT NOT NULL)" + tableOptions);
        }
        enforceCaseSensitivePlayerColumns(connection);
        ensureCurrentIndexes(connection);
    }

    private void createInventorySnapshotTable(Connection connection) throws SQLException {
        String playerNameColumn = settings.getType() == Settings.Database.Type.MYSQL
                ? "VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL"
                : "VARCHAR(16) NOT NULL";
        String payloadColumn = settings.getType() == Settings.Database.Type.MYSQL
                ? "MEDIUMBLOB NOT NULL" : "BLOB NOT NULL";
        String tableOptions = settings.getType() == Settings.Database.Type.MYSQL
                ? " ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci" : "";
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS " + INVENTORY_SNAPSHOTS_TABLE + " ("
                    + "player_name " + playerNameColumn + " PRIMARY KEY, "
                    + "player_uuid VARCHAR(36) NULL, server_name VARCHAR(128) NOT NULL, "
                    + "captured_at BIGINT NOT NULL, format_version INTEGER NOT NULL, "
                    + "payload " + payloadColumn + ", updated_at BIGINT NOT NULL)" + tableOptions);
        }
    }

    private void createBindAttemptTable(Connection connection) throws SQLException {
        String playerNameColumn = settings.getType() == Settings.Database.Type.MYSQL
                ? "VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL"
                : "VARCHAR(16) NOT NULL";
        String tableOptions = settings.getType() == Settings.Database.Type.MYSQL
                ? " ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci" : "";
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS " + BIND_ATTEMPTS_TABLE + " ("
                    + "player_name " + playerNameColumn + ", qq_id VARCHAR(20) NOT NULL, "
                    + "attempts INTEGER NOT NULL, expires_at BIGINT NOT NULL, updated_at BIGINT NOT NULL, "
                    + "PRIMARY KEY(player_name, qq_id))" + tableOptions);
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

    /** Removes QQ uniqueness while keeping exact player-name uniqueness. */
    private void migrateToVersion3(Connection connection) throws SQLException {
        if (settings.getType() == Settings.Database.Type.SQLITE) {
            migrateSqliteBindingsToVersion3(connection);
        } else if (indexExists(connection, BINDINGS_TABLE, "uq_shitbot_bindings_qq")) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("ALTER TABLE " + BINDINGS_TABLE + " DROP INDEX uq_shitbot_bindings_qq");
            }
        }
        ensureCurrentIndexes(connection);
    }

    private void migrateToVersion4(Connection connection) throws SQLException {
        createInventorySnapshotTable(connection);
        enforceCaseSensitivePlayerColumns(connection);
        ensureCurrentIndexes(connection);
    }

    private void migrateToVersion5(Connection connection) throws SQLException {
        createBindAttemptTable(connection);
        enforceCaseSensitivePlayerColumns(connection);
        ensureCurrentIndexes(connection);
    }

    private void migrateToVersion6(Connection connection) throws SQLException {
        ensureBindCodeCooldownColumn(connection);
    }

    private void ensureBindCodeCooldownColumn(Connection connection) throws SQLException {
        if (columnExists(connection, CODES_TABLE, "blocked_until")) {
            return;
        }
        try (Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE " + CODES_TABLE
                    + " ADD blocked_until BIGINT NOT NULL DEFAULT 0");
        }
    }

    private void migrateSqliteBindingsToVersion3(Connection connection) throws SQLException {
        final String temporaryTable = "shitbot_bindings_v3_tmp";
        boolean previousAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try (Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS " + temporaryTable);
            statement.execute("CREATE TABLE " + temporaryTable + " ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT, player_name VARCHAR(16) NOT NULL, "
                    + "player_uuid VARCHAR(36) NULL, qq_id VARCHAR(20) NOT NULL, "
                    + "created_at BIGINT NOT NULL, updated_at BIGINT NOT NULL, "
                    + "CONSTRAINT uq_shitbot_bindings_player UNIQUE(player_name))");
            statement.execute("INSERT INTO " + temporaryTable
                    + "(id, player_name, player_uuid, qq_id, created_at, updated_at) "
                    + "SELECT id, player_name, player_uuid, qq_id, created_at, updated_at FROM " + BINDINGS_TABLE);
            statement.execute("DROP TABLE " + BINDINGS_TABLE);
            statement.execute("ALTER TABLE " + temporaryTable + " RENAME TO " + BINDINGS_TABLE);
            connection.commit();
        } catch (SQLException exception) {
            try { connection.rollback(); } catch (SQLException rollbackError) { exception.addSuppressed(rollbackError); }
            throw exception;
        } finally {
            connection.setAutoCommit(previousAutoCommit);
        }
    }

    private void cleanupMigrationArtifacts(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            if (settings.getType() == Settings.Database.Type.MYSQL) {
                statement.execute("DROP TABLE IF EXISTS shitbot_bindings_v2_tmp, shitbot_bind_codes_v2_tmp, "
                        + "shitbot_bindings_v3_tmp, shitbot_bindings_v1_backup, shitbot_bind_codes_v1_backup");
            } else {
                statement.execute("DROP TABLE IF EXISTS shitbot_bindings_v2_tmp");
                statement.execute("DROP TABLE IF EXISTS shitbot_bind_codes_v2_tmp");
                statement.execute("DROP TABLE IF EXISTS shitbot_bindings_v3_tmp");
            }
        }
    }

    private void enforceCaseSensitivePlayerColumns(Connection connection) throws SQLException {
        if (settings.getType() != Settings.Database.Type.MYSQL) {
            return;
        }
        ensureCaseSensitivePlayerColumn(connection, BINDINGS_TABLE);
        ensureCaseSensitivePlayerColumn(connection, CODES_TABLE);
        ensureCaseSensitivePlayerColumn(connection, INVENTORY_SNAPSHOTS_TABLE);
        ensureCaseSensitivePlayerColumn(connection, BIND_ATTEMPTS_TABLE);
    }

    private void ensureCaseSensitivePlayerColumn(Connection connection, String table) throws SQLException {
        if (!tableExists(connection, table) || mysqlPlayerColumnMatches(connection, table)) {
            return;
        }
        try (Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE " + table
                    + " MODIFY player_name VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL");
        }
    }

    private boolean mysqlPlayerColumnMatches(Connection connection, String table) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT DATA_TYPE, CHARACTER_MAXIMUM_LENGTH, IS_NULLABLE, "
                        + "CHARACTER_SET_NAME, COLLATION_NAME FROM information_schema.COLUMNS "
                        + "WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=? AND COLUMN_NAME='player_name'")) {
            statement.setString(1, table);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return false;
                }
                return "varchar".equalsIgnoreCase(resultSet.getString("DATA_TYPE"))
                        && resultSet.getLong("CHARACTER_MAXIMUM_LENGTH") == 16L
                        && "NO".equalsIgnoreCase(resultSet.getString("IS_NULLABLE"))
                        && "utf8mb4".equalsIgnoreCase(resultSet.getString("CHARACTER_SET_NAME"))
                        && "utf8mb4_bin".equalsIgnoreCase(resultSet.getString("COLLATION_NAME"));
            }
        }
    }

    private void ensureCurrentIndexes(Connection connection) throws SQLException {
        ensureIndex(connection, BINDINGS_TABLE, "idx_shitbot_bindings_uuid", "player_uuid");
        ensureIndex(connection, BINDINGS_TABLE, "idx_shitbot_bindings_qq", "qq_id");
        ensureIndex(connection, CODES_TABLE, "idx_shitbot_bind_codes_expires", "expires_at");
        if (tableExists(connection, BIND_ATTEMPTS_TABLE)) {
            ensureIndex(connection, BIND_ATTEMPTS_TABLE,
                    "idx_shitbot_bind_attempts_expires", "expires_at");
        }
        if (tableExists(connection, INVENTORY_SNAPSHOTS_TABLE)) {
            ensureIndex(connection, INVENTORY_SNAPSHOTS_TABLE,
                    "idx_shitbot_inventory_captured", "captured_at");
        }
    }

    private boolean tableExists(Connection connection, String table) throws SQLException {
        DatabaseMetaData metaData = connection.getMetaData();
        String[] candidates = new String[]{table, table.toUpperCase(Locale.ROOT), table.toLowerCase(Locale.ROOT)};
        for (String candidate : candidates) {
            try (ResultSet resultSet = metaData.getTables(connection.getCatalog(), null, candidate, new String[]{"TABLE"})) {
                if (resultSet.next()) return true;
            }
        }
        return false;
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
        } catch (SQLException exception) {
            if (!indexExists(connection, table, index)) {
                throw exception;
            }
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
        final CompletableFuture<T> result = new CompletableFuture<T>();
        try {
            executor.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        if (settings.getType() == Settings.Database.Type.SQLITE) {
                            Connection connection = requireSqliteConnection();
                            configureConnection(connection);
                            result.complete(function.apply(connection));
                            return;
                        }

                        HikariDataSource current = mysqlDataSource;
                        if (current == null) {
                            throw new IllegalStateException("Database is not initialized");
                        }
                        try (Connection connection = current.getConnection()) {
                            configureConnection(connection);
                            result.complete(function.apply(connection));
                        }
                    } catch (Throwable throwable) {
                        result.completeExceptionally(throwable);
                    }
                }
            });
        } catch (RejectedExecutionException exception) {
            result.completeExceptionally(new RejectedExecutionException(
                    "Database task queue is full or shutting down", exception));
        }
        return result;
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
        if (closed.get()) {
            return false;
        }
        if (settings.getType() == Settings.Database.Type.SQLITE) {
            Connection current = sqliteConnection;
            if (current == null) {
                return false;
            }
            try {
                return !current.isClosed();
            } catch (SQLException ignored) {
                return false;
            }
        }
        HikariDataSource current = mysqlDataSource;
        return current != null && !current.isClosed();
    }

    private Connection requireSqliteConnection() throws SQLException {
        Connection current = sqliteConnection;
        if (current == null || current.isClosed()) {
            throw new SQLException("SQLite connection is not initialized or has been closed");
        }
        return current;
    }

    private static void closeQuietly(Connection connection) {
        if (connection == null) {
            return;
        }
        try {
            connection.close();
        } catch (SQLException ignored) {
            // Best-effort shutdown only.
        }
    }

    public Settings.Database.Type getType() {
        return settings.getType();
    }

    public int getActiveTaskCount() {
        return executor.getActiveCount();
    }

    public int getWorkerCount() {
        return executor.getCorePoolSize();
    }

    public int getQueuedTaskCount() {
        return executor.getQueue().size();
    }

    public int getMaximumQueuedTaskCount() {
        return settings.getMaximumQueuedTasks();
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

        Connection currentSqlite = sqliteConnection;
        sqliteConnection = null;
        closeQuietly(currentSqlite);

        HikariDataSource currentMysql = mysqlDataSource;
        mysqlDataSource = null;
        if (currentMysql != null) {
            currentMysql.close();
        }
    }

    public interface SqlFunction<T> {
        T apply(Connection connection) throws Exception;
    }
}
