package io.github.empireage.civilizations.database;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.github.empireage.civilizations.config.Settings;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class Database implements AutoCloseable {
    private final Settings.Database settings;
    private final Logger logger;
    private final ExecutorService executor;
    private final Object lifecycleGuard = new Object();
    private final AtomicReference<HikariDataSource> dataSource = new AtomicReference<>();
    private final AtomicBoolean healthy = new AtomicBoolean(false);
    private final AtomicBoolean connecting = new AtomicBoolean(false);
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public Database(Settings.Database settings, Logger logger) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.logger = Objects.requireNonNull(logger, "logger");
        ThreadFactory factory = Thread.ofPlatform().name("civilizations-db-", 0).daemon(true).factory();
        this.executor = Executors.newFixedThreadPool(Math.max(2, settings.poolSize()), factory);
    }

    public CompletableFuture<Boolean> connectAndMigrate() {
        if (closed.get()) return CompletableFuture.completedFuture(false);
        if (!connecting.compareAndSet(false, true)) return CompletableFuture.completedFuture(healthy.get());
        try {
            return CompletableFuture.supplyAsync(() -> {
                try {
                if (closed.get()) return false;
                HikariDataSource existing = dataSource.get();
                if (existing == null || existing.isClosed()) {
                    HikariConfig config = new HikariConfig();
                    config.setJdbcUrl(settings.jdbcUrl());
                    config.setUsername(settings.username());
                    config.setPassword(settings.password());
                    config.setDriverClassName("com.mysql.cj.jdbc.Driver");
                    config.setMaximumPoolSize(settings.poolSize());
                    config.setMinimumIdle(1);
                    config.setConnectionTimeout(settings.connectionTimeoutMs());
                    config.setValidationTimeout(Math.min(settings.connectionTimeoutMs(), 3000));
                    config.setInitializationFailTimeout(-1);
                    config.setPoolName("Civilizations-MySQL");
                    config.addDataSourceProperty("cachePrepStmts", "true");
                    config.addDataSourceProperty("prepStmtCacheSize", "250");
                    config.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
                    config.addDataSourceProperty("useServerPrepStmts", "true");
                    HikariDataSource replacement = new HikariDataSource(config);
                    HikariDataSource previous;
                    synchronized (lifecycleGuard) {
                        if (closed.get()) {
                            replacement.close();
                            return false;
                        }
                        previous = dataSource.getAndSet(replacement);
                    }
                    if (previous != null) previous.close();
                }
                try (Connection connection = requireDataSource().getConnection()) {
                    MigrationRunner.migrate(connection, logger);
                    try (var statement = connection.prepareStatement("SELECT 1")) {
                        statement.executeQuery();
                    }
                }
                synchronized (lifecycleGuard) {
                    if (closed.get()) return false;
                    healthy.set(true);
                    return true;
                }
                } catch (Exception exception) {
                    healthy.set(false);
                    logger.log(Level.WARNING, "MySQL connection/migration failed; mutation commands remain fail-closed: " + exception.getMessage());
                    return false;
                } finally {
                    connecting.set(false);
                }
            }, executor);
        } catch (RejectedExecutionException closedDuringSubmission) {
            connecting.set(false);
            return CompletableFuture.completedFuture(false);
        }
    }

    public <T> CompletableFuture<T> read(SqlFunction<Connection, T> operation) {
        return CompletableFuture.supplyAsync(() -> {
            ensureHealthy();
            try (Connection connection = requireDataSource().getConnection()) {
                connection.setReadOnly(true);
                return operation.apply(connection);
            } catch (Exception exception) {
                handleFailure(exception);
                throw wrap(exception);
            }
        }, executor);
    }

    /**
     * Runs a multi-query read from one repeatable-read snapshot. This is used for
     * rebuilding the in-memory protection indexes so their constituent maps can
     * never represent different committed moments.
     */
    public <T> CompletableFuture<T> consistentRead(SqlFunction<Connection, T> operation) {
        return CompletableFuture.supplyAsync(() -> {
            ensureHealthy();
            try (Connection connection = requireDataSource().getConnection()) {
                connection.setReadOnly(true);
                connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
                connection.setAutoCommit(false);
                try {
                    T value = operation.apply(connection);
                    connection.commit();
                    return value;
                } catch (Exception exception) {
                    try {
                        connection.rollback();
                    } catch (SQLException rollbackFailure) {
                        exception.addSuppressed(rollbackFailure);
                    }
                    throw exception;
                }
            } catch (Exception exception) {
                handleFailure(exception);
                throw wrap(exception);
            }
        }, executor);
    }

    public <T> CompletableFuture<T> transaction(SqlFunction<Connection, T> operation) {
        return CompletableFuture.supplyAsync(() -> {
            ensureHealthy();
            try (Connection connection = requireDataSource().getConnection()) {
                connection.setAutoCommit(false);
                connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
                try {
                    T value = operation.apply(connection);
                    connection.commit();
                    return value;
                } catch (Exception exception) {
                    try {
                        connection.rollback();
                    } catch (SQLException rollbackFailure) {
                        exception.addSuppressed(rollbackFailure);
                    }
                    throw exception;
                }
            } catch (Exception exception) {
                handleFailure(exception);
                throw wrap(exception);
            }
        }, executor);
    }

    public boolean healthy() {
        return healthy.get();
    }

    public boolean connecting() {
        return connecting.get();
    }

    public void markHealthy() {
        if (!closed.get()) healthy.set(true);
    }

    public void markUnhealthy(Throwable reason) {
        if (healthy.getAndSet(false)) logger.log(Level.WARNING, "MySQL became unavailable; mutations are locked", reason);
    }

    private void ensureHealthy() {
        if (!healthy.get()) throw new StorageUnavailableException("MySQL is unavailable");
    }

    private HikariDataSource requireDataSource() {
        HikariDataSource source = dataSource.get();
        if (source == null) throw new StorageUnavailableException("MySQL pool has not initialized");
        return source;
    }

    private void handleFailure(Exception exception) {
        Throwable cursor = exception;
        while (cursor != null) {
            if (cursor instanceof SQLException sql && sql.getSQLState() != null && sql.getSQLState().startsWith("08")) {
                markUnhealthy(exception);
                return;
            }
            cursor = cursor.getCause();
        }
    }

    private RuntimeException wrap(Exception exception) {
        if (exception instanceof RuntimeException runtime) return runtime;
        return new DatabaseException("Database operation failed", exception);
    }

    @Override
    public void close() {
        HikariDataSource source;
        synchronized (lifecycleGuard) {
            if (!closed.compareAndSet(false, true)) return;
            healthy.set(false);
            source = dataSource.getAndSet(null);
        }
        if (source != null) source.close();
        executor.shutdownNow();
    }
}
