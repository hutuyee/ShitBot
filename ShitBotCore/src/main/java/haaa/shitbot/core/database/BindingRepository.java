package haaa.shitbot.core.database;

import haaa.shitbot.core.config.Settings;
import haaa.shitbot.core.util.HashUtil;
import haaa.shitbot.core.util.TextUtil;

import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/** All SQL related to bindings and one-time verification codes. */
public final class BindingRepository {
    private static final SecureRandom RANDOM = new SecureRandom();

    private final DatabaseManager database;
    private final Settings.Binding settings;

    public BindingRepository(DatabaseManager database, Settings.Binding settings) {
        this.database = database;
        this.settings = settings;
    }

    /**
     * Looks up an exact Minecraft player name.
     *
     * <p>Player-name matching is intentionally case-sensitive. Offline-mode
     * servers derive UUIDs from the exact player-name spelling, so names such
     * as {@code H_aaa} and {@code h_AAA} must never share a binding.</p>
     */
    public CompletableFuture<Optional<BindingRecord>> findByPlayerName(final String playerName) {
        if (!TextUtil.isValidPlayerName(playerName)) {
            return CompletableFuture.completedFuture(Optional.<BindingRecord>empty());
        }
        final String cleanName = playerName.trim();
        return database.supplyAsync(new DatabaseManager.SqlFunction<Optional<BindingRecord>>() {
            @Override
            public Optional<BindingRecord> apply(Connection connection) throws SQLException {
                return findByPlayerName(connection, cleanName);
            }
        });
    }

    public CompletableFuture<Optional<BindingRecord>> findByQqId(final String qqId) {
        final String normalizedQq = qqId == null ? "" : qqId.trim();
        return database.supplyAsync(new DatabaseManager.SqlFunction<Optional<BindingRecord>>() {
            @Override
            public Optional<BindingRecord> apply(Connection connection) throws SQLException {
                return findByQqId(connection, normalizedQq);
            }
        });
    }

    public CompletableFuture<IssuedBindCode> issueCode(final String playerName) {
        if (!TextUtil.isValidPlayerName(playerName)) {
            CompletableFuture<IssuedBindCode> failed = new CompletableFuture<IssuedBindCode>();
            failed.completeExceptionally(new IllegalArgumentException("Invalid player name"));
            return failed;
        }
        final String cleanName = playerName.trim();
        final String code = generateCode();
        final String salt = HashUtil.toHex(HashUtil.randomBytes(16));
        final String hash = HashUtil.sha256Hex(salt, code);
        final long now = System.currentTimeMillis();
        final long expiresAt = now + settings.getExpireMinutes() * 60_000L;

        return database.transactionAsync(new DatabaseManager.SqlFunction<IssuedBindCode>() {
            @Override
            public IssuedBindCode apply(Connection connection) throws SQLException {
                deleteExpiredCodes(connection, now);
                if (database.getType() == Settings.Database.Type.SQLITE) {
                    try (PreparedStatement statement = connection.prepareStatement(
                            "INSERT INTO shitbot_bind_codes(player_name, code_hash, code_salt, expires_at, attempts, created_at, updated_at) "
                                    + "VALUES(?, ?, ?, ?, 0, ?, ?) "
                                    + "ON CONFLICT(player_name) DO UPDATE SET "
                                    + "code_hash=excluded.code_hash, code_salt=excluded.code_salt, "
                                    + "expires_at=excluded.expires_at, attempts=0, updated_at=excluded.updated_at")) {
                        setCodeParameters(statement, cleanName, hash, salt, expiresAt, now);
                        statement.executeUpdate();
                    }
                } else {
                    try (PreparedStatement statement = connection.prepareStatement(
                            "INSERT INTO shitbot_bind_codes(player_name, code_hash, code_salt, expires_at, attempts, created_at, updated_at) "
                                    + "VALUES(?, ?, ?, ?, 0, ?, ?) "
                                    + "ON DUPLICATE KEY UPDATE code_hash=VALUES(code_hash), "
                                    + "code_salt=VALUES(code_salt), expires_at=VALUES(expires_at), "
                                    + "attempts=0, updated_at=VALUES(updated_at)")) {
                        setCodeParameters(statement, cleanName, hash, salt, expiresAt, now);
                        statement.executeUpdate();
                    }
                }
                return new IssuedBindCode(cleanName, code, expiresAt);
            }
        });
    }

    private void setCodeParameters(PreparedStatement statement,
                                   String cleanName,
                                   String hash,
                                   String salt,
                                   long expiresAt,
                                   long now) throws SQLException {
        statement.setString(1, cleanName);
        statement.setString(2, hash);
        statement.setString(3, salt);
        statement.setLong(4, expiresAt);
        statement.setLong(5, now);
        statement.setLong(6, now);
    }

    public CompletableFuture<BindResult> bind(final String playerName,
                                               final String qqId,
                                               final String submittedCode) {
        if (!TextUtil.isValidPlayerName(playerName)
                || !TextUtil.isValidQqId(qqId)
                || submittedCode == null
                || submittedCode.trim().isEmpty()) {
            return CompletableFuture.completedFuture(BindResult.of(BindResult.Status.INVALID_INPUT));
        }
        final String cleanName = playerName.trim();
        final String cleanQq = qqId.trim();
        final String cleanCode = submittedCode.trim().toUpperCase(java.util.Locale.ROOT);

        return database.transactionAsync(new DatabaseManager.SqlFunction<BindResult>() {
            @Override
            public BindResult apply(Connection connection) throws SQLException {
                long now = System.currentTimeMillis();
                CodeRow codeRow = readCode(connection, cleanName, database.getType() == Settings.Database.Type.MYSQL);
                if (codeRow == null) {
                    return BindResult.of(BindResult.Status.EXPIRED_OR_MISSING);
                }
                if (codeRow.expiresAt <= now) {
                    deleteCode(connection, cleanName);
                    return BindResult.of(BindResult.Status.EXPIRED_OR_MISSING);
                }
                if (codeRow.attempts >= settings.getMaximumAttempts()) {
                    deleteCode(connection, cleanName);
                    return BindResult.of(BindResult.Status.TOO_MANY_ATTEMPTS);
                }

                String submittedHash = HashUtil.sha256Hex(codeRow.salt, cleanCode);
                if (!HashUtil.constantTimeEquals(codeRow.hash, submittedHash)) {
                    int newAttempts = codeRow.attempts + 1;
                    if (newAttempts >= settings.getMaximumAttempts()) {
                        deleteCode(connection, cleanName);
                        return BindResult.of(BindResult.Status.TOO_MANY_ATTEMPTS);
                    }
                    try (PreparedStatement statement = connection.prepareStatement(
                            "UPDATE shitbot_bind_codes SET attempts=?, updated_at=? WHERE player_name=?")) {
                        statement.setInt(1, newAttempts);
                        statement.setLong(2, now);
                        statement.setString(3, cleanName);
                        statement.executeUpdate();
                    }
                    return BindResult.of(BindResult.Status.INVALID_CODE);
                }

                Optional<BindingRecord> playerBinding = findByPlayerName(connection, cleanName);
                Optional<BindingRecord> qqBinding = findByQqId(connection, cleanQq);
                if (playerBinding.isPresent()) {
                    BindingRecord existing = playerBinding.get();
                    if (cleanQq.equals(existing.getQqId())) {
                        deleteCode(connection, cleanName);
                        return BindResult.success(BindResult.Status.ALREADY_BOUND_SAME, existing);
                    }
                    return BindResult.of(BindResult.Status.PLAYER_ALREADY_BOUND);
                }
                if (qqBinding.isPresent()) {
                    return BindResult.of(BindResult.Status.QQ_ALREADY_BOUND);
                }

                try (PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO shitbot_bindings(player_name, player_uuid, qq_id, created_at, updated_at) "
                                + "VALUES(?, ?, ?, ?, ?)")) {
                    statement.setString(1, cleanName);
                    statement.setNull(2, Types.VARCHAR);
                    statement.setString(3, cleanQq);
                    statement.setLong(4, now);
                    statement.setLong(5, now);
                    statement.executeUpdate();
                } catch (SQLException conflict) {
                    Optional<BindingRecord> playerAfterConflict = findByPlayerName(connection, cleanName);
                    if (playerAfterConflict.isPresent()) {
                        if (cleanQq.equals(playerAfterConflict.get().getQqId())) {
                            deleteCode(connection, cleanName);
                            return BindResult.success(BindResult.Status.ALREADY_BOUND_SAME, playerAfterConflict.get());
                        }
                        return BindResult.of(BindResult.Status.PLAYER_ALREADY_BOUND);
                    }
                    if (findByQqId(connection, cleanQq).isPresent()) {
                        return BindResult.of(BindResult.Status.QQ_ALREADY_BOUND);
                    }
                    throw conflict;
                }
                deleteCode(connection, cleanName);
                BindingRecord created = new BindingRecord(cleanName, null, cleanQq, now, now);
                return BindResult.success(BindResult.Status.SUCCESS, created);
            }
        });
    }

    public CompletableFuture<Void> updateUuid(final String playerName, final String uuid) {
        if (!TextUtil.isValidPlayerName(playerName) || uuid == null || uuid.trim().isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        final String cleanName = playerName.trim();
        final String cleanUuid = uuid.trim();
        return database.supplyAsync(new DatabaseManager.SqlFunction<Void>() {
            @Override
            public Void apply(Connection connection) throws SQLException {
                try (PreparedStatement statement = connection.prepareStatement(
                        "UPDATE shitbot_bindings SET player_uuid=?, updated_at=? "
                                + "WHERE player_name=? AND (player_uuid IS NULL OR player_uuid<>?)")) {
                    statement.setString(1, cleanUuid);
                    statement.setLong(2, System.currentTimeMillis());
                    statement.setString(3, cleanName);
                    statement.setString(4, cleanUuid);
                    statement.executeUpdate();
                }
                return null;
            }
        });
    }

    public CompletableFuture<Integer> cleanupExpiredCodes() {
        final long now = System.currentTimeMillis();
        return database.supplyAsync(new DatabaseManager.SqlFunction<Integer>() {
            @Override
            public Integer apply(Connection connection) throws SQLException {
                return Integer.valueOf(deleteExpiredCodes(connection, now));
            }
        });
    }

    private Optional<BindingRecord> findByPlayerName(Connection connection, String playerName) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT player_name, player_uuid, qq_id, created_at, updated_at "
                        + "FROM shitbot_bindings WHERE player_name=?")) {
            statement.setString(1, playerName);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(readBinding(resultSet)) : Optional.<BindingRecord>empty();
            }
        }
    }

    private Optional<BindingRecord> findByQqId(Connection connection, String qqId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT player_name, player_uuid, qq_id, created_at, updated_at FROM shitbot_bindings WHERE qq_id=?")) {
            statement.setString(1, qqId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(readBinding(resultSet)) : Optional.<BindingRecord>empty();
            }
        }
    }

    private BindingRecord readBinding(ResultSet resultSet) throws SQLException {
        return new BindingRecord(
                resultSet.getString("player_name"),
                resultSet.getString("player_uuid"),
                resultSet.getString("qq_id"),
                resultSet.getLong("created_at"),
                resultSet.getLong("updated_at"));
    }

    private CodeRow readCode(Connection connection, String playerName, boolean lock) throws SQLException {
        String sql = "SELECT code_hash, code_salt, expires_at, attempts "
                + "FROM shitbot_bind_codes WHERE player_name=?"
                + (lock ? " FOR UPDATE" : "");
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, playerName);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return null;
                }
                return new CodeRow(
                        resultSet.getString("code_hash"),
                        resultSet.getString("code_salt"),
                        resultSet.getLong("expires_at"),
                        resultSet.getInt("attempts"));
            }
        }
    }

    private void deleteCode(Connection connection, String playerName) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM shitbot_bind_codes WHERE player_name=?")) {
            statement.setString(1, playerName);
            statement.executeUpdate();
        }
    }

    private int deleteExpiredCodes(Connection connection, long now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM shitbot_bind_codes WHERE expires_at<=?")) {
            statement.setLong(1, now);
            return statement.executeUpdate();
        }
    }

    private String generateCode() {
        String alphabet = settings.getCodeAlphabet();
        StringBuilder builder = new StringBuilder(settings.getCodeLength());
        for (int i = 0; i < settings.getCodeLength(); i++) {
            builder.append(alphabet.charAt(RANDOM.nextInt(alphabet.length())));
        }
        return builder.toString();
    }

    private static final class CodeRow {
        private final String hash;
        private final String salt;
        private final long expiresAt;
        private final int attempts;

        private CodeRow(String hash, String salt, long expiresAt, int attempts) {
            this.hash = hash;
            this.salt = salt;
            this.expiresAt = expiresAt;
            this.attempts = attempts;
        }
    }
}
