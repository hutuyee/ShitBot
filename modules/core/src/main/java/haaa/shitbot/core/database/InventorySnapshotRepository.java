package haaa.shitbot.core.database;

import haaa.shitbot.core.config.Settings;
import haaa.shitbot.core.inventory.InventorySnapshot;
import haaa.shitbot.core.inventory.InventorySnapshotCodec;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/** All persistence operations for the latest inventory snapshot of each exact player name. */
public final class InventorySnapshotRepository {
    private static final String TABLE = "shitbot_inventory_snapshots";

    private final DatabaseManager database;
    private final InventorySnapshotCodec codec = new InventorySnapshotCodec();

    public InventorySnapshotRepository(DatabaseManager database) {
        this.database = database;
    }

    public CompletableFuture<Void> save(final InventorySnapshot snapshot) {
        if (snapshot == null) {
            return CompletableFuture.completedFuture(null);
        }
        return saveAll(Collections.singletonList(snapshot));
    }

    public CompletableFuture<Void> saveAll(final List<InventorySnapshot> snapshots) {
        if (snapshots == null || snapshots.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        final List<InventorySnapshot> copy = new ArrayList<InventorySnapshot>(snapshots);
        return database.transactionAsync(new DatabaseManager.SqlFunction<Void>() {
            @Override
            public Void apply(Connection connection) throws Exception {
                List<EncodedSnapshot> encoded = encodeValidSnapshots(copy);
                if (encoded.isEmpty()) {
                    return null;
                }
                if (database.getType() == Settings.Database.Type.SQLITE) {
                    saveSqlite(connection, encoded);
                } else {
                    saveMysql(connection, encoded);
                }
                return null;
            }
        });
    }

    public CompletableFuture<Optional<InventorySnapshot>> findByPlayerName(final String playerName) {
        final String cleanName = playerName == null ? "" : playerName.trim();
        if (cleanName.isEmpty()) {
            return CompletableFuture.completedFuture(Optional.<InventorySnapshot>empty());
        }
        return database.supplyAsync(new DatabaseManager.SqlFunction<Optional<InventorySnapshot>>() {
            @Override
            public Optional<InventorySnapshot> apply(Connection connection) throws Exception {
                try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT payload FROM " + TABLE + " WHERE player_name=?")) {
                    statement.setString(1, cleanName);
                    try (ResultSet resultSet = statement.executeQuery()) {
                        if (!resultSet.next()) {
                            return Optional.empty();
                        }
                        return Optional.of(codec.decode(resultSet.getBytes(1)));
                    }
                }
            }
        });
    }

    public CompletableFuture<Optional<InventorySnapshot>> findFreshest(final List<String> playerNames) {
        final List<String> names = cleanNames(playerNames);
        if (names.isEmpty()) {
            return CompletableFuture.completedFuture(Optional.<InventorySnapshot>empty());
        }
        return database.supplyAsync(new DatabaseManager.SqlFunction<Optional<InventorySnapshot>>() {
            @Override
            public Optional<InventorySnapshot> apply(Connection connection) throws Exception {
                InventorySnapshot freshest = null;
                // Stay below SQLite's common 999-bound-variable limit even when an
                // administrator allows an unusually large number of IDs per QQ.
                for (int start = 0; start < names.size(); start += 400) {
                    int end = Math.min(names.size(), start + 400);
                    StringBuilder sql = new StringBuilder("SELECT payload FROM ")
                            .append(TABLE).append(" WHERE ");
                    for (int i = start; i < end; i++) {
                        if (i > start) {
                            sql.append(" OR ");
                        }
                        sql.append("player_name=?");
                    }
                    sql.append(" ORDER BY captured_at DESC LIMIT 1");
                    try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
                        for (int i = start; i < end; i++) {
                            statement.setString(i - start + 1, names.get(i));
                        }
                        try (ResultSet resultSet = statement.executeQuery()) {
                            if (resultSet.next()) {
                                InventorySnapshot candidate = codec.decode(resultSet.getBytes(1));
                                if (freshest == null || candidate.getCapturedAt() > freshest.getCapturedAt()) {
                                    freshest = candidate;
                                }
                            }
                        }
                    }
                }
                return Optional.ofNullable(freshest);
            }
        });
    }

    public CompletableFuture<Integer> deleteOlderThan(final long cutoffTimestamp) {
        return database.supplyAsync(new DatabaseManager.SqlFunction<Integer>() {
            @Override
            public Integer apply(Connection connection) throws SQLException {
                try (PreparedStatement statement = connection.prepareStatement(
                        "DELETE FROM " + TABLE + " WHERE captured_at<?")) {
                    statement.setLong(1, cutoffTimestamp);
                    return Integer.valueOf(statement.executeUpdate());
                }
            }
        });
    }

    private List<EncodedSnapshot> encodeValidSnapshots(List<InventorySnapshot> snapshots) throws Exception {
        List<EncodedSnapshot> result = new ArrayList<EncodedSnapshot>(snapshots.size());
        for (InventorySnapshot snapshot : snapshots) {
            if (snapshot == null || snapshot.getPlayerName().trim().isEmpty()) {
                continue;
            }
            result.add(new EncodedSnapshot(snapshot, codec.encode(snapshot)));
        }
        return result;
    }

    private void saveSqlite(Connection connection, List<EncodedSnapshot> snapshots) throws SQLException {
        String updateSql = "UPDATE " + TABLE + " SET player_uuid=?, server_name=?, captured_at=?, "
                + "format_version=?, payload=?, updated_at=? WHERE player_name=? AND captured_at<=?";
        String insertSql = "INSERT OR IGNORE INTO " + TABLE
                + "(player_name, player_uuid, server_name, captured_at, format_version, payload, updated_at) "
                + "VALUES(?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement update = connection.prepareStatement(updateSql);
             PreparedStatement insert = connection.prepareStatement(insertSql)) {
            long now = System.currentTimeMillis();
            for (EncodedSnapshot encoded : snapshots) {
                InventorySnapshot snapshot = encoded.snapshot;
                setNullableString(update, 1, snapshot.getPlayerUuid());
                update.setString(2, snapshot.getServerName());
                update.setLong(3, snapshot.getCapturedAt());
                update.setInt(4, InventorySnapshot.FORMAT_VERSION);
                update.setBytes(5, encoded.payload);
                update.setLong(6, now);
                update.setString(7, snapshot.getPlayerName());
                update.setLong(8, snapshot.getCapturedAt());
                if (update.executeUpdate() == 0) {
                    insert.setString(1, snapshot.getPlayerName());
                    setNullableString(insert, 2, snapshot.getPlayerUuid());
                    insert.setString(3, snapshot.getServerName());
                    insert.setLong(4, snapshot.getCapturedAt());
                    insert.setInt(5, InventorySnapshot.FORMAT_VERSION);
                    insert.setBytes(6, encoded.payload);
                    insert.setLong(7, now);
                    insert.executeUpdate();
                }
            }
        }
    }

    private void saveMysql(Connection connection, List<EncodedSnapshot> snapshots) throws SQLException {
        String sql = "INSERT INTO " + TABLE + "(player_name, player_uuid, server_name, captured_at, "
                + "format_version, payload, updated_at) VALUES(?, ?, ?, ?, ?, ?, ?) "
                + "ON DUPLICATE KEY UPDATE "
                + "player_uuid=IF(VALUES(captured_at)>=captured_at, VALUES(player_uuid), player_uuid), "
                + "server_name=IF(VALUES(captured_at)>=captured_at, VALUES(server_name), server_name), "
                + "format_version=IF(VALUES(captured_at)>=captured_at, VALUES(format_version), format_version), "
                + "payload=IF(VALUES(captured_at)>=captured_at, VALUES(payload), payload), "
                + "updated_at=IF(VALUES(captured_at)>=captured_at, VALUES(updated_at), updated_at), "
                + "captured_at=GREATEST(captured_at, VALUES(captured_at))";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            long now = System.currentTimeMillis();
            for (EncodedSnapshot encoded : snapshots) {
                InventorySnapshot snapshot = encoded.snapshot;
                statement.setString(1, snapshot.getPlayerName());
                setNullableString(statement, 2, snapshot.getPlayerUuid());
                statement.setString(3, snapshot.getServerName());
                statement.setLong(4, snapshot.getCapturedAt());
                statement.setInt(5, InventorySnapshot.FORMAT_VERSION);
                statement.setBytes(6, encoded.payload);
                statement.setLong(7, now);
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private void setNullableString(PreparedStatement statement, int index, String value) throws SQLException {
        if (value == null || value.trim().isEmpty()) {
            statement.setNull(index, Types.VARCHAR);
        } else {
            statement.setString(index, value);
        }
    }

    private List<String> cleanNames(List<String> names) {
        if (names == null || names.isEmpty()) {
            return Collections.emptyList();
        }
        Set<String> unique = new LinkedHashSet<String>();
        for (String name : names) {
            if (name != null && !name.trim().isEmpty()) {
                unique.add(name.trim());
            }
        }
        return new ArrayList<String>(unique);
    }

    private static final class EncodedSnapshot {
        private final InventorySnapshot snapshot;
        private final byte[] payload;

        private EncodedSnapshot(InventorySnapshot snapshot, byte[] payload) {
            this.snapshot = snapshot;
            this.payload = payload;
        }
    }
}
