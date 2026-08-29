package haaa.shitbot.core.database;

import haaa.shitbot.core.config.Settings;
import haaa.shitbot.core.util.NamedThreadFactory;

import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLTransientConnectionException;
import java.sql.Statement;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Small MySQL-only pool used to avoid embedding HikariCP in every platform JAR. */
final class MysqlConnectionPool implements AutoCloseable {
    private static final long VALIDATION_BYPASS_MS = 500L;

    private final JdbcDriverRegistry drivers;
    private final Settings.Database settings;
    private final ArrayBlockingQueue<Entry> idle;
    private final ScheduledExecutorService housekeeper = Executors.newSingleThreadScheduledExecutor(
            new NamedThreadFactory("shitbot-mysql-pool", true));
    private final AtomicInteger total = new AtomicInteger();
    private final AtomicBoolean closed = new AtomicBoolean();

    MysqlConnectionPool(JdbcDriverRegistry drivers, Settings.Database settings)
            throws IOException, SQLException {
        this.drivers = drivers;
        this.settings = settings;
        this.idle = new ArrayBlockingQueue<Entry>(settings.getMaximumPoolSize());
        boolean success = false;
        try {
            int initial = Math.max(1, settings.getMinimumIdle());
            for (int index = 0; index < initial; index++) {
                Entry entry = createReservedEntry();
                idle.offer(entry);
            }
            housekeeper.scheduleWithFixedDelay(new Runnable() {
                @Override
                public void run() {
                    maintainIdleConnections();
                }
            }, 30L, 30L, TimeUnit.SECONDS);
            success = true;
        } finally {
            if (!success) {
                close();
            }
        }
    }

    Borrowed borrow() throws IOException, SQLException {
        if (closed.get()) {
            throw new SQLException("MySQL connection pool is closed");
        }
        long timeoutNanos = TimeUnit.MILLISECONDS.toNanos(settings.getConnectionTimeoutMs());
        long deadline = System.nanoTime() + timeoutNanos;

        while (true) {
            Entry entry = idle.poll();
            if (entry != null) {
                if (prepareForBorrow(entry)) {
                    return new Borrowed(this, entry);
                }
                destroy(entry);
                continue;
            }

            if (reserveSlot()) {
                try {
                    return new Borrowed(this, openEntry());
                } catch (IOException exception) {
                    total.decrementAndGet();
                    throw exception;
                } catch (SQLException exception) {
                    total.decrementAndGet();
                    throw exception;
                } catch (RuntimeException exception) {
                    total.decrementAndGet();
                    throw exception;
                }
            }

            long remaining = deadline - System.nanoTime();
            if (remaining <= 0L) {
                throw new SQLTransientConnectionException("Timed out waiting for a MySQL connection after "
                        + settings.getConnectionTimeoutMs() + "ms");
            }
            try {
                // Periodically re-check capacity because a broken active connection
                // can be discarded without placing an entry back in the queue.
                long waitNanos = Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(100L));
                entry = idle.poll(waitNanos, TimeUnit.NANOSECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new SQLTransientConnectionException("Interrupted while waiting for a MySQL connection",
                        exception);
            }
            if (entry == null) {
                continue;
            }
            if (prepareForBorrow(entry)) {
                return new Borrowed(this, entry);
            }
            destroy(entry);
        }
    }

    private Entry createReservedEntry() throws IOException, SQLException {
        if (!reserveSlot()) {
            throw new SQLException("MySQL connection pool capacity is exhausted");
        }
        try {
            return openEntry();
        } catch (IOException exception) {
            total.decrementAndGet();
            throw exception;
        } catch (SQLException exception) {
            total.decrementAndGet();
            throw exception;
        } catch (RuntimeException exception) {
            total.decrementAndGet();
            throw exception;
        }
    }

    private Entry openEntry() throws IOException, SQLException {
        Connection connection = drivers.openConfiguredConnection(settings);
        boolean success = false;
        try {
            connection.setAutoCommit(true);
            long now = System.currentTimeMillis();
            Entry entry = new Entry(connection, now);
            success = true;
            return entry;
        } finally {
            if (!success) {
                closeQuietly(connection);
            }
        }
    }

    private boolean reserveSlot() {
        while (!closed.get()) {
            int current = total.get();
            if (current >= settings.getMaximumPoolSize()) {
                return false;
            }
            if (total.compareAndSet(current, current + 1)) {
                return true;
            }
        }
        return false;
    }

    private boolean prepareForBorrow(Entry entry) {
        if (closed.get() || entry.retired.get()) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (now - entry.createdAt >= settings.getMaximumLifetimeMs()) {
            return false;
        }
        if (now - entry.lastReturnedAt >= settings.getIdleTimeoutMs()
                && total.get() > settings.getMinimumIdle()) {
            return false;
        }
        if (now - entry.lastReturnedAt < VALIDATION_BYPASS_MS) {
            return true;
        }
        try {
            if (!validate(entry.connection)) {
                return false;
            }
            entry.lastValidatedAt = now;
            return true;
        } catch (SQLException exception) {
            return false;
        }
    }

    private boolean validate(Connection connection) throws SQLException {
        if (connection.isClosed()) {
            return false;
        }
        int timeoutSeconds = (int) Math.max(1L,
                (settings.getValidationTimeoutMs() + 999L) / 1000L);
        try {
            return connection.isValid(timeoutSeconds);
        } catch (AbstractMethodError ignored) {
            return validateWithQuery(connection, timeoutSeconds);
        } catch (SQLFeatureNotSupportedException ignored) {
            return validateWithQuery(connection, timeoutSeconds);
        }
    }

    private boolean validateWithQuery(Connection connection, int timeoutSeconds) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            try {
                statement.setQueryTimeout(timeoutSeconds);
            } catch (AbstractMethodError ignored) {
                // Connector/J 5.1 variants may not implement this JDBC method.
            } catch (SQLFeatureNotSupportedException ignored) {
                // The query itself is still a valid liveness check.
            }
            return statement.execute("SELECT 1");
        }
    }

    private void maintainIdleConnections() {
        if (closed.get()) {
            return;
        }
        try {
            long now = System.currentTimeMillis();
            for (Entry entry : idle) {
                boolean expired = now - entry.createdAt >= settings.getMaximumLifetimeMs();
                boolean idleExpired = now - entry.lastReturnedAt >= settings.getIdleTimeoutMs()
                        && total.get() > settings.getMinimumIdle();
                boolean keepaliveDue = settings.getKeepaliveTimeMs() > 0L
                        && now - entry.lastValidatedAt >= settings.getKeepaliveTimeMs();
                if (!expired && !idleExpired && !keepaliveDue) {
                    continue;
                }
                if (!idle.remove(entry)) {
                    continue;
                }
                if (expired || idleExpired) {
                    destroy(entry);
                } else {
                    try {
                        if (validate(entry.connection)) {
                            entry.lastValidatedAt = now;
                            entry.lastReturnedAt = now;
                            if (!idle.offer(entry)) {
                                destroy(entry);
                            }
                        } else {
                            destroy(entry);
                        }
                    } catch (SQLException exception) {
                        destroy(entry);
                    }
                }
            }
            fillMinimumIdle();
        } catch (Throwable ignored) {
            // A maintenance failure must not stop later keepalive passes.
        }
    }

    private void fillMinimumIdle() {
        while (!closed.get() && total.get() < settings.getMinimumIdle()) {
            if (!reserveSlot()) {
                return;
            }
            try {
                Entry entry = openEntry();
                if (!idle.offer(entry)) {
                    destroy(entry);
                }
            } catch (IOException ignored) {
                total.decrementAndGet();
                return;
            } catch (SQLException ignored) {
                total.decrementAndGet();
                return;
            } catch (RuntimeException ignored) {
                total.decrementAndGet();
                return;
            }
        }
    }

    private void recycle(Entry entry, boolean broken) throws SQLException {
        if (entry == null || entry.retired.get()) {
            return;
        }
        if (broken || closed.get()) {
            destroy(entry);
            return;
        }

        try {
            if (entry.connection.isClosed()) {
                destroy(entry);
                return;
            }
            if (!entry.connection.getAutoCommit()) {
                entry.connection.rollback();
                entry.connection.setAutoCommit(true);
            }
            if (entry.connection.isReadOnly()) {
                entry.connection.setReadOnly(false);
            }
            entry.connection.clearWarnings();
            entry.lastReturnedAt = System.currentTimeMillis();
            if (entry.lastReturnedAt - entry.createdAt >= settings.getMaximumLifetimeMs()
                    || !idle.offer(entry)) {
                destroy(entry);
            }
        } catch (SQLException exception) {
            destroy(entry);
            throw exception;
        } catch (AbstractMethodError ignored) {
            entry.lastReturnedAt = System.currentTimeMillis();
            if (!idle.offer(entry)) {
                destroy(entry);
            }
        }
    }

    private void destroy(Entry entry) {
        if (entry != null && entry.retired.compareAndSet(false, true)) {
            total.decrementAndGet();
            closeQuietly(entry.connection);
        }
    }

    boolean isClosed() {
        return closed.get();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        housekeeper.shutdownNow();
        Entry entry;
        while ((entry = idle.poll()) != null) {
            destroy(entry);
        }
    }

    private static void closeQuietly(Connection connection) {
        if (connection == null) {
            return;
        }
        try {
            connection.close();
        } catch (SQLException ignored) {
            // Best-effort physical connection shutdown.
        }
    }

    static final class Borrowed implements AutoCloseable {
        private final MysqlConnectionPool pool;
        private final Entry entry;
        private final AtomicBoolean returned = new AtomicBoolean();
        private volatile boolean broken;

        private Borrowed(MysqlConnectionPool pool, Entry entry) {
            this.pool = pool;
            this.entry = entry;
        }

        Connection connection() {
            return entry.connection;
        }

        void invalidate() {
            broken = true;
        }

        @Override
        public void close() throws SQLException {
            if (returned.compareAndSet(false, true)) {
                pool.recycle(entry, broken);
            }
        }
    }

    private static final class Entry {
        private final Connection connection;
        private final long createdAt;
        private final AtomicBoolean retired = new AtomicBoolean();
        private volatile long lastReturnedAt;
        private volatile long lastValidatedAt;

        private Entry(Connection connection, long now) {
            this.connection = connection;
            this.createdAt = now;
            this.lastReturnedAt = now;
            this.lastValidatedAt = now;
        }
    }
}
