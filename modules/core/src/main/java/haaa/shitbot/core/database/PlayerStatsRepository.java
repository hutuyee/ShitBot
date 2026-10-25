package haaa.shitbot.core.database;

import haaa.shitbot.core.util.TextUtil;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/** Persists login sessions and accumulated online time. */
public final class PlayerStatsRepository {
    private static final String TABLE = "shitbot_player_stats";

    private final DatabaseManager database;

    public PlayerStatsRepository(DatabaseManager database) {
        this.database = database;
    }

    public CompletableFuture<Void> startSession(final String playerName, final String playerUuid) {
        if (!TextUtil.isValidPlayerName(playerName)) {
            return CompletableFuture.completedFuture(null);
        }
        final String cleanName = playerName.trim();
        final String cleanUuid = playerUuid == null ? "" : playerUuid.trim();
        return database.transactionAsync(new DatabaseManager.SqlFunction<Void>() {
            @Override
            public Void apply(Connection connection) throws SQLException {
                long now = System.currentTimeMillis();
                PlayerStats current = find(connection, cleanName);
                if (current == null) {
                    insert(connection, cleanName, cleanUuid, 0L, Long.valueOf(now), now);
                    return null;
                }
                long total = current.getTotalOnlineSeconds() + elapsedSeconds(current.getSessionStartedAt(), now);
                update(connection, cleanName, cleanUuid, total, Long.valueOf(now), now);
                return null;
            }
        });
    }

    public CompletableFuture<Void> endSession(final String playerName, final String playerUuid) {
        if (!TextUtil.isValidPlayerName(playerName)) {
            return CompletableFuture.completedFuture(null);
        }
        final String cleanName = playerName.trim();
        final String cleanUuid = playerUuid == null ? "" : playerUuid.trim();
        return database.transactionAsync(new DatabaseManager.SqlFunction<Void>() {
            @Override
            public Void apply(Connection connection) throws SQLException {
                long now = System.currentTimeMillis();
                PlayerStats current = find(connection, cleanName);
                if (current == null) {
                    insert(connection, cleanName, cleanUuid, 0L, null, now);
                    return null;
                }
                long total = current.getTotalOnlineSeconds() + elapsedSeconds(current.getSessionStartedAt(), now);
                update(connection, cleanName, cleanUuid, total, null, now);
                return null;
            }
        });
    }

    public CompletableFuture<Optional<PlayerStats>> findByPlayerName(final String playerName) {
        if (!TextUtil.isValidPlayerName(playerName)) {
            return CompletableFuture.completedFuture(Optional.<PlayerStats>empty());
        }
        final String cleanName = playerName.trim();
        return database.supplyAsync(new DatabaseManager.SqlFunction<Optional<PlayerStats>>() {
            @Override
            public Optional<PlayerStats> apply(Connection connection) throws SQLException {
                PlayerStats stats = find(connection, cleanName);
                return Optional.ofNullable(stats);
            }
        });
    }

    private PlayerStats find(Connection connection, String playerName) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT player_name, player_uuid, total_online_seconds, session_started_at "
                        + "FROM " + TABLE + " WHERE player_name=?")) {
            statement.setString(1, playerName);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return null;
                }
                long sessionStarted = resultSet.getLong(4);
                Long session = resultSet.wasNull() ? null : Long.valueOf(sessionStarted);
                return new PlayerStats(resultSet.getString(1), resultSet.getString(2),
                        resultSet.getLong(3), session);
            }
        }
    }

    private void insert(Connection connection,
                        String playerName,
                        String playerUuid,
                        long total,
                        Long sessionStartedAt,
                        long now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO " + TABLE + "(player_name, player_uuid, total_online_seconds, "
                        + "session_started_at, updated_at) VALUES(?, ?, ?, ?, ?)")) {
            statement.setString(1, playerName);
            setNullableString(statement, 2, playerUuid);
            statement.setLong(3, Math.max(0L, total));
            setNullableLong(statement, 4, sessionStartedAt);
            statement.setLong(5, now);
            statement.executeUpdate();
        }
    }

    private void update(Connection connection,
                        String playerName,
                        String playerUuid,
                        long total,
                        Long sessionStartedAt,
                        long now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE " + TABLE + " SET player_uuid=?, total_online_seconds=?, "
                        + "session_started_at=?, updated_at=? WHERE player_name=?")) {
            setNullableString(statement, 1, playerUuid);
            statement.setLong(2, Math.max(0L, total));
            setNullableLong(statement, 3, sessionStartedAt);
            statement.setLong(4, now);
            statement.setString(5, playerName);
            statement.executeUpdate();
        }
    }

    private long elapsedSeconds(Long startedAt, long now) {
        if (startedAt == null || startedAt.longValue() <= 0L || now <= startedAt.longValue()) {
            return 0L;
        }
        return (now - startedAt.longValue()) / 1000L;
    }

    private void setNullableString(PreparedStatement statement, int index, String value) throws SQLException {
        if (value == null || value.trim().isEmpty()) {
            statement.setNull(index, Types.VARCHAR);
        } else {
            statement.setString(index, value);
        }
    }

    private void setNullableLong(PreparedStatement statement, int index, Long value) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.BIGINT);
        } else {
            statement.setLong(index, value.longValue());
        }
    }
}
