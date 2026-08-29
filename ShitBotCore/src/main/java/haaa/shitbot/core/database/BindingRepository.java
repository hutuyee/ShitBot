package haaa.shitbot.core.database;

import haaa.shitbot.core.config.Settings;
import haaa.shitbot.core.util.HashUtil;
import haaa.shitbot.core.util.TextUtil;

import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoField;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** All SQL related to bindings and one-time verification codes. */
public final class BindingRepository {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int BIND_LOCK_STRIPES = 64;
    private static final ConcurrentMap<String, IssuedBindCode> ISSUED_CODES =
            new ConcurrentHashMap<String, IssuedBindCode>();

    private final DatabaseManager database;
    private final Settings.Binding settings;
    private final Object[] bindLocks = new Object[BIND_LOCK_STRIPES];

    public BindingRepository(DatabaseManager database, Settings.Binding settings) {
        this.database = database;
        this.settings = settings;
        for (int i = 0; i < bindLocks.length; i++) bindLocks[i] = new Object();
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

    /** Returns every exact player name owned by one QQ, newest binding first. */
    public CompletableFuture<List<BindingRecord>> findAllByQqId(final String qqId) {
        if (!TextUtil.isValidQqId(qqId)) {
            return CompletableFuture.completedFuture(Collections.<BindingRecord>emptyList());
        }
        final String cleanQq = qqId.trim();
        return database.supplyAsync(new DatabaseManager.SqlFunction<List<BindingRecord>>() {
            @Override
            public List<BindingRecord> apply(Connection connection) throws SQLException {
                return findAllByQqId(connection, cleanQq);
            }
        });
    }

    /** Removes every binding owned by the exact QQ number. */
    public CompletableFuture<Integer> removeByQqId(final String qqId) {
        return removeByQqIdAndReturnBindings(qqId).thenApply(
                new java.util.function.Function<List<BindingRecord>, Integer>() {
                    @Override
                    public Integer apply(List<BindingRecord> bindings) {
                        return Integer.valueOf(bindings.size());
                    }
                });
    }

    /** Removes every binding owned by the exact QQ number and returns the removed records. */
    public CompletableFuture<List<BindingRecord>> removeByQqIdAndReturnBindings(final String qqId) {
        if (!TextUtil.isValidQqId(qqId)) {
            return CompletableFuture.completedFuture(Collections.<BindingRecord>emptyList());
        }
        final String cleanQq = qqId.trim();
        final Object lock = bindLocks[(cleanQq.hashCode() & Integer.MAX_VALUE) % bindLocks.length];
        return database.transactionAsync(new DatabaseManager.SqlFunction<List<BindingRecord>>() {
            @Override
            public List<BindingRecord> apply(Connection connection) throws SQLException {
                synchronized (lock) {
                    List<BindingRecord> bindings = findAllByQqId(connection, cleanQq, true);
                    if (bindings.isEmpty()) {
                        return bindings;
                    }
                    try (PreparedStatement statement = connection.prepareStatement(
                            "DELETE FROM shitbot_bindings WHERE qq_id=?")) {
                        statement.setString(1, cleanQq);
                        statement.executeUpdate();
                    }
                    return bindings;
                }
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
        final long now = System.currentTimeMillis();

        return database.transactionAsync(new DatabaseManager.SqlFunction<IssuedBindCode>() {
            @Override
            public IssuedBindCode apply(Connection connection) throws SQLException {
                deleteExpiredCodes(connection, now);
                deleteExpiredAttempts(connection, now);
                CodeRow existing = readCode(
                        connection,
                        cleanName,
                        database.getType() == Settings.Database.Type.MYSQL);
                IssuedBindCode cached = matchingCachedCode(cleanName, existing, now);
                if (cached != null) {
                    return cached;
                }

                String code = generateCode();
                String salt = HashUtil.toHex(HashUtil.randomBytes(16));
                String hash = HashUtil.sha256Hex(salt, code);
                long expiresAt = now + settings.getExpireMinutes() * 60_000L;
                deleteAttempts(connection, cleanName);
                if (database.getType() == Settings.Database.Type.SQLITE) {
                    int updated;
                    try (PreparedStatement statement = connection.prepareStatement(
                            "UPDATE shitbot_bind_codes SET code_hash=?, code_salt=?, expires_at=?, "
                                    + "attempts=0, blocked_until=0, updated_at=? WHERE player_name=?")) {
                        statement.setString(1, hash);
                        statement.setString(2, salt);
                        statement.setLong(3, expiresAt);
                        statement.setLong(4, now);
                        statement.setString(5, cleanName);
                        updated = statement.executeUpdate();
                    }
                    if (updated == 0) {
                        try (PreparedStatement statement = connection.prepareStatement(
                                "INSERT INTO shitbot_bind_codes(player_name, code_hash, code_salt, expires_at, "
                                        + "attempts, blocked_until, created_at, updated_at) "
                                        + "VALUES(?, ?, ?, ?, 0, 0, ?, ?)")) {
                            setCodeParameters(statement, cleanName, hash, salt, expiresAt, now);
                            statement.executeUpdate();
                        }
                    }
                } else {
                    try (PreparedStatement statement = connection.prepareStatement(
                            "INSERT INTO shitbot_bind_codes(player_name, code_hash, code_salt, expires_at, "
                                    + "attempts, blocked_until, created_at, updated_at) "
                                    + "VALUES(?, ?, ?, ?, 0, 0, ?, ?) "
                                    + "ON DUPLICATE KEY UPDATE code_hash=VALUES(code_hash), "
                                    + "code_salt=VALUES(code_salt), expires_at=VALUES(expires_at), "
                                    + "attempts=0, blocked_until=0, updated_at=VALUES(updated_at)")) {
                        setCodeParameters(statement, cleanName, hash, salt, expiresAt, now);
                        statement.executeUpdate();
                    }
                }
                IssuedBindCode issued = new IssuedBindCode(cleanName, code, expiresAt);
                return issued;
            }
        }).thenApply(new java.util.function.Function<IssuedBindCode, IssuedBindCode>() {
            @Override
            public IssuedBindCode apply(IssuedBindCode issued) {
                ISSUED_CODES.put(cleanName, issued);
                return issued;
            }
        });
    }

    private IssuedBindCode matchingCachedCode(String playerName, CodeRow row, long now) {
        if (row == null || row.expiresAt <= now) {
            ISSUED_CODES.remove(playerName);
            return null;
        }
        IssuedBindCode cached = ISSUED_CODES.get(playerName);
        if (cached == null) {
            return null;
        }
        if (cached.getExpiresAt() != row.expiresAt
                || !HashUtil.constantTimeEquals(row.hash, HashUtil.sha256Hex(row.salt, cached.getCode()))) {
            ISSUED_CODES.remove(playerName, cached);
            return null;
        }
        return cached;
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

    public CompletableFuture<BindResult> bind(final String playerName, final String qqId, final String submittedCode) {
        if (!TextUtil.isValidPlayerName(playerName) || !TextUtil.isValidQqId(qqId)
                || submittedCode == null || submittedCode.trim().isEmpty()) {
            return CompletableFuture.completedFuture(BindResult.of(BindResult.Status.INVALID_INPUT));
        }
        final String cleanName = playerName.trim();
        final String cleanQq = qqId.trim();
        final String cleanCode = submittedCode.trim().toUpperCase(Locale.ROOT);
        final Object lock = bindLocks[(cleanQq.hashCode() & Integer.MAX_VALUE) % bindLocks.length];
        return database.transactionAsync(new DatabaseManager.SqlFunction<BindResult>() {
            @Override
            public BindResult apply(Connection connection) throws SQLException {
                synchronized (lock) {
                    return bindInTransaction(connection, cleanName, cleanQq, cleanCode);
                }
            }
        });
    }

    private BindResult bindInTransaction(Connection connection,
                                             String playerName,
                                             String qqId,
                                             String submittedCode) throws SQLException {
        long now = System.currentTimeMillis();
        CodeRow codeRow = readCode(
                connection,
                playerName,
                database.getType() == Settings.Database.Type.MYSQL);

        if (codeRow == null) {
            return BindResult.of(BindResult.Status.EXPIRED_OR_MISSING);
        }
        if (codeRow.expiresAt <= now) {
            deleteCode(connection, playerName);
            return BindResult.of(BindResult.Status.EXPIRED_OR_MISSING);
        }
        if (codeRow.blockedUntil > now) {
            return BindResult.of(BindResult.Status.TOO_MANY_ATTEMPTS);
        }
        if (codeRow.attempts >= settings.getMaximumTotalAttempts()) {
            blockCodeTemporarily(connection, playerName, now);
            return BindResult.of(BindResult.Status.TOO_MANY_ATTEMPTS);
        }
        int attempts = readAttempts(connection, playerName, qqId, now);
        if (attempts >= settings.getMaximumAttemptsPerQq()) {
            return BindResult.of(BindResult.Status.TOO_MANY_ATTEMPTS);
        }

        String submittedHash = HashUtil.sha256Hex(codeRow.salt, submittedCode);
        if (!HashUtil.constantTimeEquals(codeRow.hash, submittedHash)) {
            attempts = incrementAttempts(connection, playerName, qqId, codeRow.expiresAt, now);
            int totalAttempts = incrementTotalAttempts(connection, playerName, now, codeRow.attempts);
            if (totalAttempts >= settings.getMaximumTotalAttempts()) {
                blockCodeTemporarily(connection, playerName, now);
                return BindResult.of(BindResult.Status.TOO_MANY_ATTEMPTS);
            }
            if (attempts >= settings.getMaximumAttemptsPerQq()) {
                return BindResult.of(BindResult.Status.TOO_MANY_ATTEMPTS);
            }
            return BindResult.of(BindResult.Status.INVALID_CODE);
        }

        Optional<BindingRecord> existingPlayer = findByPlayerName(connection, playerName);
        if (existingPlayer.isPresent()) {
            if (qqId.equals(existingPlayer.get().getQqId())) {
                deleteCode(connection, playerName);
                return BindResult.success(BindResult.Status.ALREADY_BOUND_SAME, existingPlayer.get());
            }
            return BindResult.of(BindResult.Status.PLAYER_ALREADY_BOUND);
        }

        int currentBindingCount = countBindingsByQq(connection, qqId, true);
        if (currentBindingCount >= settings.getMaximumIdsPerQq()) {
            return BindResult.of(BindResult.Status.QQ_BINDING_LIMIT_REACHED);
        }

        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO shitbot_bindings(player_name, player_uuid, qq_id, created_at, updated_at) "
                        + "VALUES(?, ?, ?, ?, ?)")) {
            statement.setString(1, playerName);
            statement.setNull(2, Types.VARCHAR);
            statement.setString(3, qqId);
            statement.setLong(4, now);
            statement.setLong(5, now);
            statement.executeUpdate();
        } catch (SQLException conflict) {
            Optional<BindingRecord> bindingAfterConflict = findByPlayerName(connection, playerName);
            if (bindingAfterConflict.isPresent()) {
                if (qqId.equals(bindingAfterConflict.get().getQqId())) {
                    deleteCode(connection, playerName);
                    return BindResult.success(
                            BindResult.Status.ALREADY_BOUND_SAME,
                            bindingAfterConflict.get());
                }
                return BindResult.of(BindResult.Status.PLAYER_ALREADY_BOUND);
            }
            if (countBindingsByQq(connection, qqId, false) >= settings.getMaximumIdsPerQq()) {
                return BindResult.of(BindResult.Status.QQ_BINDING_LIMIT_REACHED);
            }
            throw conflict;
        }

        deleteCode(connection, playerName);
        return BindResult.success(
                BindResult.Status.SUCCESS,
                new BindingRecord(playerName, null, qqId, now, now));
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

    /**
     * Imports EasyBot associations asynchronously through
     * SocialAccount -> PlayerSocialAccount -> Player.
     *
     * <p>The maximum number of distinct player IDs owned by one QQ is checked
     * before the first target row is written. If the configured ShitBot limit
     * is too small, the entire migration is rejected. The target writes also
     * run in one transaction, so an existing ShitBot binding cannot cause a
     * partially imported result.</p>
     */
    public CompletableFuture<EasyBotMigrationResult> importEasyBotBindings(final Path sourcePath) {
        if (sourcePath == null) {
            CompletableFuture<EasyBotMigrationResult> failed =
                    new CompletableFuture<EasyBotMigrationResult>();
            failed.completeExceptionally(new IllegalArgumentException("EasyBot database path is null"));
            return failed;
        }

        return database.transactionAsync(new DatabaseManager.SqlFunction<EasyBotMigrationResult>() {
            @Override
            public EasyBotMigrationResult apply(Connection target) throws Exception {
                try (Connection source = database.openSqliteConnection(sourcePath)) {
                    try (Statement statement = source.createStatement()) {
                        statement.execute("PRAGMA query_only=ON");
                        statement.execute("PRAGMA busy_timeout=5000");
                    }

                    EasyBotSchema schema = inspectEasyBotSchema(source);
                    EasyBotSourceLimit sourceLimit = findLargestEasyBotBindingCount(source, schema);
                    validateSourceBindingLimit(sourceLimit);
                    return importEasyBotRows(source, target, schema);
                }
            }
        });
    }

    private void validateSourceBindingLimit(EasyBotSourceLimit sourceLimit) {
        int configuredLimit = settings.getMaximumIdsPerQq();
        if (sourceLimit.bindingCount <= configuredLimit) {
            return;
        }

        throw new IllegalStateException(
                "EasyBot 迁移已取消：QQ " + sourceLimit.qqId
                        + " 在 EasyBot 中绑定了 " + sourceLimit.bindingCount
                        + " 个游戏ID，但 ShitBot 当前最多允许 " + configuredLimit
                        + " 个。请修改 binding.maximum-ids-per-qq"
                        + (settings.isAllowMultipleIdsPerQq()
                        ? ""
                        : " 并开启 binding.allow-multiple-ids-per-qq")
                        + "，执行 /shitbot reload 后重新迁移。");
    }

    /** Finds the largest distinct, valid player-name count owned by one lowercase qq account. */
    private EasyBotSourceLimit findLargestEasyBotBindingCount(Connection source,
                                                               EasyBotSchema schema) throws SQLException {
        String sql = schema.hasPlayerRelations
                ? relationalBindingCountQuery()
                : legacyBindingCountQuery();

        String currentQq = null;
        int currentCount = 0;
        String largestQq = "";
        int largestCount = 0;

        try (PreparedStatement statement = source.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                String qqId = trimToEmpty(resultSet.getString("QqId"));
                String playerName = trimToEmpty(resultSet.getString("PlayerName"));
                if (!TextUtil.isValidQqId(qqId) || !TextUtil.isValidPlayerName(playerName)) {
                    continue;
                }

                if (!qqId.equals(currentQq)) {
                    if (currentQq != null && currentCount > largestCount) {
                        largestQq = currentQq;
                        largestCount = currentCount;
                    }
                    currentQq = qqId;
                    currentCount = 0;
                }
                currentCount++;
            }
        }

        if (currentQq != null && currentCount > largestCount) {
            largestQq = currentQq;
            largestCount = currentCount;
        }
        return new EasyBotSourceLimit(largestQq, largestCount);
    }

    private EasyBotMigrationResult importEasyBotRows(Connection source,
                                                       Connection target,
                                                       EasyBotSchema schema) throws SQLException {
        MigrationAccumulator counters = new MigrationAccumulator();
        Map<String, Integer> qqBindingCounts = new HashMap<String, Integer>();
        String sourceQuery = schema.hasPlayerRelations ? relationalQuery() : legacyQuery();
        String countQuery = "SELECT id FROM shitbot_bindings WHERE qq_id=?"
                + (database.getType() == Settings.Database.Type.MYSQL ? " FOR UPDATE" : "");

        try (PreparedStatement findPlayer = target.prepareStatement(
                     "SELECT qq_id, player_uuid FROM shitbot_bindings WHERE player_name=?");
             PreparedStatement countQqBindings = target.prepareStatement(countQuery);
             PreparedStatement insert = target.prepareStatement(
                     "INSERT INTO shitbot_bindings(player_name, player_uuid, qq_id, created_at, updated_at) "
                             + "VALUES(?, ?, ?, ?, ?)");
             PreparedStatement updateUuid = target.prepareStatement(
                     "UPDATE shitbot_bindings SET player_uuid=?, updated_at=? "
                             + "WHERE player_name=? AND player_uuid IS NULL");
             PreparedStatement deleteCode = target.prepareStatement(
                     "DELETE FROM shitbot_bind_codes WHERE player_name=?");
             PreparedStatement sourceStatement = source.prepareStatement(sourceQuery);
             ResultSet resultSet = sourceStatement.executeQuery()) {

            long socialId = Long.MIN_VALUE;
            boolean qqPlatform = false;
            boolean hasRelatedPlayer = false;
            String qqId = "";
            String fallbackPlayerName = "";
            String socialCreatedTime = null;
            String socialUpdatedTime = null;

            while (resultSet.next()) {
                long nextSocialId = resultSet.getLong("SocialId");
                if (nextSocialId != socialId) {
                    if (socialId != Long.MIN_VALUE && qqPlatform && !hasRelatedPlayer) {
                        counters.fallbackRows++;
                        importCandidate(
                                socialId,
                                null,
                                qqId,
                                fallbackPlayerName,
                                null,
                                socialCreatedTime,
                                socialUpdatedTime,
                                null,
                                null,
                                findPlayer,
                                countQqBindings,
                                insert,
                                updateUuid,
                                deleteCode,
                                qqBindingCounts,
                                counters);
                    }

                    socialId = nextSocialId;
                    counters.totalRows++;
                    qqPlatform = "qq".equals(resultSet.getString("Platform"));
                    if (qqPlatform) {
                        counters.qqRows++;
                    } else {
                        counters.nonQqRows++;
                    }
                    hasRelatedPlayer = false;
                    qqId = trimToEmpty(resultSet.getString("QqId"));
                    fallbackPlayerName = trimToEmpty(resultSet.getString("SocialName"));
                    socialCreatedTime = resultSet.getString("SocialCreatedTime");
                    socialUpdatedTime = resultSet.getString("SocialUpdatedTime");
                }

                if (!qqPlatform) {
                    continue;
                }

                Object playerId = resultSet.getObject("PlayerId");
                if (playerId == null) {
                    continue;
                }

                hasRelatedPlayer = true;
                counters.linkedPlayerRows++;
                importCandidate(
                        socialId,
                        String.valueOf(playerId),
                        qqId,
                        trimToEmpty(resultSet.getString("PlayerName")),
                        resultSet.getString("PlayerUuid"),
                        socialCreatedTime,
                        socialUpdatedTime,
                        resultSet.getString("PlayerCreatedTime"),
                        resultSet.getString("PlayerUpdatedTime"),
                        findPlayer,
                        countQqBindings,
                        insert,
                        updateUuid,
                        deleteCode,
                        qqBindingCounts,
                        counters);
            }

            if (socialId != Long.MIN_VALUE && qqPlatform && !hasRelatedPlayer) {
                counters.fallbackRows++;
                importCandidate(
                        socialId,
                        null,
                        qqId,
                        fallbackPlayerName,
                        null,
                        socialCreatedTime,
                        socialUpdatedTime,
                        null,
                        null,
                        findPlayer,
                        countQqBindings,
                        insert,
                        updateUuid,
                        deleteCode,
                        qqBindingCounts,
                        counters);
            }
        }
        return counters.toResult();
    }

    private void importCandidate(long socialId,
                                 String playerId,
                                 String qqId,
                                 String playerName,
                                 String playerUuidValue,
                                 String socialCreatedTime,
                                 String socialUpdatedTime,
                                 String playerCreatedTime,
                                 String playerUpdatedTime,
                                 PreparedStatement findPlayer,
                                 PreparedStatement countQqBindings,
                                 PreparedStatement insert,
                                 PreparedStatement updateUuid,
                                 PreparedStatement deleteCode,
                                 Map<String, Integer> qqBindingCounts,
                                 MigrationAccumulator counters) throws SQLException {
        if (!TextUtil.isValidPlayerName(playerName) || !TextUtil.isValidQqId(qqId)) {
            counters.invalidRows++;
            return;
        }

        String playerUuid = normalizePlayerUuid(playerUuidValue);
        long now = System.currentTimeMillis();
        long socialCreated = parseEasyBotTime(socialCreatedTime, now);
        long socialUpdated = parseEasyBotTime(socialUpdatedTime, socialCreated);
        long playerCreated = parseEasyBotTime(playerCreatedTime, socialCreated);
        long playerUpdated = parseEasyBotTime(playerUpdatedTime, playerCreated);
        long createdAt = Math.max(socialCreated, playerCreated);
        long updatedAt = Math.max(createdAt, Math.max(socialUpdated, playerUpdated));

        ExistingPlayerBinding existing = findExistingPlayer(findPlayer, playerName);
        if (existing != null) {
            if (qqId.equals(existing.qqId)) {
                if (existing.playerUuid == null && playerUuid != null) {
                    updateMissingUuid(updateUuid, playerName, playerUuid, updatedAt);
                }
                counters.alreadyPresentRows++;
                deletePendingCode(deleteCode, playerName);
            } else {
                counters.playerConflictRows++;
            }
            return;
        }

        int currentCount = getCachedQqBindingCount(
                countQqBindings,
                qqBindingCounts,
                qqId);
        if (currentCount >= settings.getMaximumIdsPerQq()) {
            throw new IllegalStateException(
                    "EasyBot 迁移已取消：QQ " + qqId
                            + " 与 ShitBot 现有数据合并后至少需要 " + (currentCount + 1)
                            + " 个绑定名额，但当前最多允许 " + settings.getMaximumIdsPerQq()
                            + " 个。请修改 binding.maximum-ids-per-qq，执行 /shitbot reload 后重试。");
        }

        try {
            insert.clearParameters();
            insert.setString(1, playerName);
            if (playerUuid == null) {
                insert.setNull(2, Types.VARCHAR);
            } else {
                insert.setString(2, playerUuid);
            }
            insert.setString(3, qqId);
            insert.setLong(4, createdAt);
            insert.setLong(5, updatedAt);
            insert.executeUpdate();

            qqBindingCounts.put(qqId, Integer.valueOf(currentCount + 1));
            deletePendingCode(deleteCode, playerName);
            counters.importedRows++;
        } catch (SQLException conflict) {
            ExistingPlayerBinding afterConflict = findExistingPlayer(findPlayer, playerName);
            if (afterConflict != null && qqId.equals(afterConflict.qqId)) {
                if (afterConflict.playerUuid == null && playerUuid != null) {
                    updateMissingUuid(updateUuid, playerName, playerUuid, updatedAt);
                }
                counters.alreadyPresentRows++;
                deletePendingCode(deleteCode, playerName);
            } else if (afterConflict != null) {
                counters.playerConflictRows++;
            } else {
                throw new SQLException(
                        "Failed to import EasyBot SocialAccount Id=" + socialId
                                + (playerId == null ? "" : ", Player Id=" + playerId),
                        conflict);
            }
        }
    }

    private int getCachedQqBindingCount(PreparedStatement countStatement,
                                        Map<String, Integer> cache,
                                        String qqId) throws SQLException {
        Integer cached = cache.get(qqId);
        if (cached != null) {
            return cached.intValue();
        }

        countStatement.clearParameters();
        countStatement.setString(1, qqId);
        int count = 0;
        try (ResultSet resultSet = countStatement.executeQuery()) {
            while (resultSet.next()) {
                count++;
            }
        }
        cache.put(qqId, Integer.valueOf(count));
        return count;
    }

    private EasyBotSchema inspectEasyBotSchema(Connection source) throws SQLException {
        Set<String> socialColumns = tableColumns(source, "SocialAccount");
        if (socialColumns.isEmpty()) {
            throw new SQLException("EasyBot database does not contain table SocialAccount");
        }
        requireColumns(
                "SocialAccount",
                socialColumns,
                "id",
                "platform",
                "uuid",
                "name",
                "createdtime",
                "lastupdatedtime");

        Set<String> relationColumns = tableColumns(source, "PlayerSocialAccount");
        Set<String> playerColumns = tableColumns(source, "Player");
        boolean hasPlayerRelations = !relationColumns.isEmpty() && !playerColumns.isEmpty();
        if (hasPlayerRelations) {
            requireColumns(
                    "PlayerSocialAccount",
                    relationColumns,
                    "socialaccountsid",
                    "usersid");
            requireColumns(
                    "Player",
                    playerColumns,
                    "id",
                    "name",
                    "uuid",
                    "createdtime",
                    "lastupdatedtime");
        }
        return new EasyBotSchema(hasPlayerRelations);
    }

    private Set<String> tableColumns(Connection connection, String table) throws SQLException {
        Set<String> columns = new HashSet<String>();
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("PRAGMA table_info(\"" + table + "\")")) {
            while (resultSet.next()) {
                String name = resultSet.getString("name");
                if (name != null) {
                    columns.add(name.toLowerCase(Locale.ROOT));
                }
            }
        }
        return columns;
    }

    private void requireColumns(String table,
                                Set<String> columns,
                                String... requiredColumns) throws SQLException {
        Set<String> missing = new HashSet<String>(Arrays.asList(requiredColumns));
        missing.removeAll(columns);
        if (!missing.isEmpty()) {
            throw new SQLException("EasyBot " + table + " is missing columns: " + missing);
        }
    }

    private String relationalQuery() {
        return "SELECT sa.\"Id\" AS \"SocialId\", "
                + "sa.\"Platform\" AS \"Platform\", "
                + "sa.\"Uuid\" AS \"QqId\", "
                + "sa.\"Name\" AS \"SocialName\", "
                + "sa.\"CreatedTime\" AS \"SocialCreatedTime\", "
                + "sa.\"LastUpdatedTime\" AS \"SocialUpdatedTime\", "
                + "p.\"Id\" AS \"PlayerId\", "
                + "p.\"Name\" AS \"PlayerName\", "
                + "p.\"Uuid\" AS \"PlayerUuid\", "
                + "p.\"CreatedTime\" AS \"PlayerCreatedTime\", "
                + "p.\"LastUpdatedTime\" AS \"PlayerUpdatedTime\" "
                + "FROM \"SocialAccount\" sa "
                + "LEFT JOIN \"PlayerSocialAccount\" psa "
                + "ON psa.\"SocialAccountsId\"=sa.\"Id\" "
                + "LEFT JOIN \"Player\" p ON p.\"Id\"=psa.\"UsersId\" "
                + "ORDER BY sa.\"Id\", p.\"Id\"";
    }

    private String legacyQuery() {
        return "SELECT sa.\"Id\" AS \"SocialId\", "
                + "sa.\"Platform\" AS \"Platform\", "
                + "sa.\"Uuid\" AS \"QqId\", "
                + "sa.\"Name\" AS \"SocialName\", "
                + "sa.\"CreatedTime\" AS \"SocialCreatedTime\", "
                + "sa.\"LastUpdatedTime\" AS \"SocialUpdatedTime\", "
                + "NULL AS \"PlayerId\", NULL AS \"PlayerName\", "
                + "NULL AS \"PlayerUuid\", NULL AS \"PlayerCreatedTime\", "
                + "NULL AS \"PlayerUpdatedTime\" "
                + "FROM \"SocialAccount\" sa ORDER BY sa.\"Id\"";
    }

    private String relationalBindingCountQuery() {
        return "SELECT DISTINCT candidate_qq COLLATE BINARY AS \"QqId\", "
                + "candidate_name COLLATE BINARY AS \"PlayerName\" "
                + "FROM ("
                + "SELECT sa.\"Uuid\" AS candidate_qq, p.\"Name\" AS candidate_name "
                + "FROM \"SocialAccount\" sa "
                + "INNER JOIN \"PlayerSocialAccount\" psa "
                + "ON psa.\"SocialAccountsId\"=sa.\"Id\" "
                + "INNER JOIN \"Player\" p ON p.\"Id\"=psa.\"UsersId\" "
                + "WHERE sa.\"Platform\" COLLATE BINARY='qq' "
                + "UNION ALL "
                + "SELECT sa.\"Uuid\" AS candidate_qq, sa.\"Name\" AS candidate_name "
                + "FROM \"SocialAccount\" sa "
                + "WHERE sa.\"Platform\" COLLATE BINARY='qq' "
                + "AND NOT EXISTS ("
                + "SELECT 1 FROM \"PlayerSocialAccount\" psa2 "
                + "INNER JOIN \"Player\" p2 ON p2.\"Id\"=psa2.\"UsersId\" "
                + "WHERE psa2.\"SocialAccountsId\"=sa.\"Id\")"
                + ") candidates "
                + "ORDER BY candidate_qq COLLATE BINARY, candidate_name COLLATE BINARY";
    }

    private String legacyBindingCountQuery() {
        return "SELECT DISTINCT sa.\"Uuid\" COLLATE BINARY AS \"QqId\", "
                + "sa.\"Name\" COLLATE BINARY AS \"PlayerName\" "
                + "FROM \"SocialAccount\" sa "
                + "WHERE sa.\"Platform\" COLLATE BINARY='qq' "
                + "ORDER BY sa.\"Uuid\" COLLATE BINARY, sa.\"Name\" COLLATE BINARY";
    }

    private ExistingPlayerBinding findExistingPlayer(PreparedStatement statement,
                                                       String playerName) throws SQLException {
        statement.clearParameters();
        statement.setString(1, playerName);
        try (ResultSet resultSet = statement.executeQuery()) {
            return resultSet.next()
                    ? new ExistingPlayerBinding(
                    resultSet.getString("qq_id"),
                    resultSet.getString("player_uuid"))
                    : null;
        }
    }

    private void updateMissingUuid(PreparedStatement statement,
                                   String playerName,
                                   String playerUuid,
                                   long updatedAt) throws SQLException {
        statement.clearParameters();
        statement.setString(1, playerUuid);
        statement.setLong(2, updatedAt);
        statement.setString(3, playerName);
        statement.executeUpdate();
    }

    private void deletePendingCode(PreparedStatement statement,
                                   String playerName) throws SQLException {
        statement.clearParameters();
        statement.setString(1, playerName);
        statement.executeUpdate();
    }

    private long parseEasyBotTime(String value, long fallback) {
        if (value == null || value.trim().isEmpty()) {
            return fallback;
        }
        String clean = value.trim();
        try {
            return Instant.parse(clean).toEpochMilli();
        } catch (DateTimeParseException ignored) {
            // Try the next supported EasyBot timestamp representation.
        }
        try {
            return OffsetDateTime.parse(clean).toInstant().toEpochMilli();
        } catch (DateTimeParseException ignored) {
            // Try the local timestamp representation below.
        }
        try {
            DateTimeFormatter formatter = new DateTimeFormatterBuilder()
                    .appendPattern("yyyy-MM-dd HH:mm:ss")
                    .optionalStart()
                    .appendFraction(ChronoField.NANO_OF_SECOND, 0, 9, true)
                    .optionalEnd()
                    .toFormatter(Locale.ROOT);
            return LocalDateTime.parse(clean.replace('T', ' '), formatter)
                    .atZone(ZoneId.systemDefault())
                    .toInstant()
                    .toEpochMilli();
        } catch (DateTimeParseException ignored) {
            return fallback;
        }
    }

    private String normalizePlayerUuid(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        String clean = value.trim();
        if (clean.matches("[0-9a-fA-F]{32}")) {
            clean = clean.substring(0, 8) + '-'
                    + clean.substring(8, 12) + '-'
                    + clean.substring(12, 16) + '-'
                    + clean.substring(16, 20) + '-'
                    + clean.substring(20);
        }
        try {
            return UUID.fromString(clean).toString();
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    public CompletableFuture<Integer> cleanupExpiredCodes() {
        final long now = System.currentTimeMillis();
        cleanupExpiredCachedCodes(now);
        return database.supplyAsync(new DatabaseManager.SqlFunction<Integer>() {
            @Override
            public Integer apply(Connection connection) throws SQLException {
                int deletedCodes = deleteExpiredCodes(connection, now);
                deleteExpiredAttempts(connection, now);
                return Integer.valueOf(deletedCodes);
            }
        });
    }

    private void cleanupExpiredCachedCodes(long now) {
        for (Map.Entry<String, IssuedBindCode> entry : ISSUED_CODES.entrySet()) {
            IssuedBindCode issued = entry.getValue();
            if (issued.getExpiresAt() <= now) {
                ISSUED_CODES.remove(entry.getKey(), issued);
            }
        }
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

    private List<BindingRecord> findAllByQqId(Connection connection, String qqId) throws SQLException {
        return findAllByQqId(connection, qqId, false);
    }

    private List<BindingRecord> findAllByQqId(Connection connection,
                                              String qqId,
                                              boolean lock) throws SQLException {
        List<BindingRecord> result = new ArrayList<BindingRecord>();
        String sql = "SELECT player_name, player_uuid, qq_id, created_at, updated_at "
                + "FROM shitbot_bindings WHERE qq_id=? ORDER BY updated_at DESC, id DESC"
                + (lock && database.getType() == Settings.Database.Type.MYSQL ? " FOR UPDATE" : "");
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, qqId);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    result.add(readBinding(resultSet));
                }
            }
        }
        return result;
    }

    private Optional<BindingRecord> findByQqId(Connection connection, String qqId) throws SQLException {
        String sql = "SELECT player_name, player_uuid, qq_id, created_at, updated_at "
                + "FROM shitbot_bindings WHERE qq_id=? ORDER BY id LIMIT 1";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, qqId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next()
                        ? Optional.of(readBinding(resultSet))
                        : Optional.<BindingRecord>empty();
            }
        }
    }

    /**
     * Counts a QQ's current bindings. On MySQL the locking form uses the qq_id
     * index and prevents concurrent imports/binds from consuming the same slot.
     */
    private int countBindingsByQq(Connection connection,
                                  String qqId,
                                  boolean lock) throws SQLException {
        String sql = "SELECT id FROM shitbot_bindings WHERE qq_id=?"
                + (lock && database.getType() == Settings.Database.Type.MYSQL
                ? " FOR UPDATE"
                : "");
        int count = 0;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, qqId);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    count++;
                }
            }
        }
        return count;
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
        String sql = "SELECT code_hash, code_salt, expires_at, attempts, blocked_until "
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
                        resultSet.getInt("attempts"),
                        resultSet.getLong("blocked_until"));
            }
        }
    }

    private int incrementTotalAttempts(Connection connection,
                                       String playerName,
                                       long now,
                                       int previousAttempts) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE shitbot_bind_codes SET attempts=attempts+1, updated_at=? WHERE player_name=?")) {
            statement.setLong(1, now);
            statement.setString(2, playerName);
            if (statement.executeUpdate() != 1) {
                throw new SQLException("Bind code disappeared while recording an attempt");
            }
        }
        return previousAttempts + 1;
    }

    private void blockCodeTemporarily(Connection connection,
                                      String playerName,
                                      long now) throws SQLException {
        long blockedUntil = now + settings.getTotalAttemptCooldownSeconds() * 1000L;
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE shitbot_bind_codes SET attempts=0, blocked_until=?, updated_at=? WHERE player_name=?")) {
            statement.setLong(1, blockedUntil);
            statement.setLong(2, now);
            statement.setString(3, playerName);
            if (statement.executeUpdate() != 1) {
                throw new SQLException("Bind code disappeared while applying attempt cooldown");
            }
        }
    }

    private int readAttempts(Connection connection,
                             String playerName,
                             String qqId,
                             long now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT attempts, expires_at FROM shitbot_bind_attempts WHERE player_name=? AND qq_id=?")) {
            statement.setString(1, playerName);
            statement.setString(2, qqId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return 0;
                }
                if (resultSet.getLong("expires_at") > now) {
                    return resultSet.getInt("attempts");
                }
            }
        }
        deleteAttempts(connection, playerName, qqId);
        return 0;
    }

    private int incrementAttempts(Connection connection,
                                  String playerName,
                                  String qqId,
                                  long expiresAt,
                                  long now) throws SQLException {
        if (database.getType() == Settings.Database.Type.SQLITE) {
            int updated;
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE shitbot_bind_attempts SET attempts=attempts+1, expires_at=?, updated_at=? "
                            + "WHERE player_name=? AND qq_id=?")) {
                statement.setLong(1, expiresAt);
                statement.setLong(2, now);
                statement.setString(3, playerName);
                statement.setString(4, qqId);
                updated = statement.executeUpdate();
            }
            if (updated == 0) {
                try (PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO shitbot_bind_attempts(player_name, qq_id, attempts, expires_at, updated_at) "
                                + "VALUES(?, ?, 1, ?, ?)")) {
                    statement.setString(1, playerName);
                    statement.setString(2, qqId);
                    statement.setLong(3, expiresAt);
                    statement.setLong(4, now);
                    statement.executeUpdate();
                }
            }
        } else {
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO shitbot_bind_attempts(player_name, qq_id, attempts, expires_at, updated_at) "
                            + "VALUES(?, ?, 1, ?, ?) ON DUPLICATE KEY UPDATE "
                            + "attempts=attempts+1, expires_at=VALUES(expires_at), updated_at=VALUES(updated_at)")) {
                statement.setString(1, playerName);
                statement.setString(2, qqId);
                statement.setLong(3, expiresAt);
                statement.setLong(4, now);
                statement.executeUpdate();
            }
        }
        return readAttempts(connection, playerName, qqId, now);
    }

    private void deleteCode(Connection connection, String playerName) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM shitbot_bind_codes WHERE player_name=?")) {
            statement.setString(1, playerName);
            statement.executeUpdate();
        }
        deleteAttempts(connection, playerName);
        ISSUED_CODES.remove(playerName);
    }

    private void deleteAttempts(Connection connection, String playerName) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM shitbot_bind_attempts WHERE player_name=?")) {
            statement.setString(1, playerName);
            statement.executeUpdate();
        }
    }

    private void deleteAttempts(Connection connection, String playerName, String qqId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM shitbot_bind_attempts WHERE player_name=? AND qq_id=?")) {
            statement.setString(1, playerName);
            statement.setString(2, qqId);
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

    private void deleteExpiredAttempts(Connection connection, long now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM shitbot_bind_attempts WHERE expires_at<=?")) {
            statement.setLong(1, now);
            statement.executeUpdate();
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

    private static final class MigrationAccumulator {
        private int totalRows;
        private int qqRows;
        private int linkedPlayerRows;
        private int fallbackRows;
        private int importedRows;
        private int alreadyPresentRows;
        private int playerConflictRows;
        private int qqConflictRows;
        private int invalidRows;
        private int nonQqRows;

        private EasyBotMigrationResult toResult() {
            return new EasyBotMigrationResult(
                    totalRows,
                    qqRows,
                    linkedPlayerRows,
                    fallbackRows,
                    importedRows,
                    alreadyPresentRows,
                    playerConflictRows,
                    qqConflictRows,
                    invalidRows,
                    nonQqRows);
        }
    }

    private static final class EasyBotSchema {
        private final boolean hasPlayerRelations;

        private EasyBotSchema(boolean hasPlayerRelations) {
            this.hasPlayerRelations = hasPlayerRelations;
        }
    }

    private static final class EasyBotSourceLimit {
        private final String qqId;
        private final int bindingCount;

        private EasyBotSourceLimit(String qqId, int bindingCount) {
            this.qqId = qqId;
            this.bindingCount = bindingCount;
        }
    }

    private static final class ExistingPlayerBinding {
        private final String qqId;
        private final String playerUuid;

        private ExistingPlayerBinding(String qqId, String playerUuid) {
            this.qqId = qqId;
            this.playerUuid = playerUuid;
        }
    }

    private static final class CodeRow {
        private final String hash;
        private final String salt;
        private final long expiresAt;
        private final int attempts;
        private final long blockedUntil;

        private CodeRow(String hash, String salt, long expiresAt, int attempts, long blockedUntil) {
            this.hash = hash;
            this.salt = salt;
            this.expiresAt = expiresAt;
            this.attempts = attempts;
            this.blockedUntil = blockedUntil;
        }
    }
}
