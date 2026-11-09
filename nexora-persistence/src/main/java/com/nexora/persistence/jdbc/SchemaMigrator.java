package com.nexora.persistence.jdbc;

import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.flywaydb.core.api.output.MigrateResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Applies the store's Flyway migrations at startup and answers whether the schema is still the
 * one they produced.
 *
 * <p>Location and history table are namespaced so an application that runs its own Flyway over
 * the same database neither picks up these migrations nor shares their history.
 */
final class SchemaMigrator {

    static final String DEFAULT_LOCATION = "classpath:db/nexora/migration";
    static final String HISTORY_TABLE = "nexora_schema_history";
    static final int MIGRATION_CONNECTIONS = 2;
    /**
     * Processes starting together on an empty database race to create the history table: the
     * losers find it created but not yet baselined, and Flyway fails them. Flyway's own lock covers
     * everything after that moment, so a short wait and another attempt always gets through.
     */
    static final int MAX_ATTEMPTS = 5;

    private static final long RETRY_BASE_DELAY_MS = 200;

    private static final Logger log = LoggerFactory.getLogger(SchemaMigrator.class);
    // A schema Flyway has not seen yet is baselined below V1, so V1 still runs on it. That covers both
    // a store created before Flyway (V1 fills in missing tables) and a schema shared with other tables.
    private static final String BASELINE_VERSION = "0";
    // Flyway creates its history table with quoted lowercase names, so queries must quote them too.
    private static final String HISTORY_QUERY =
            "SELECT \"version\", \"success\" FROM \"" + HISTORY_TABLE + "\" WHERE \"version\" IS NOT NULL";

    private final MigrationVersion migratedVersion;

    private SchemaMigrator(MigrationVersion migratedVersion) {
        this.migratedVersion = migratedVersion;
    }

    /**
     * Applies pending migrations from {@code location} over connections from {@code dataSource}.
     * Flyway holds two connections at once (one locks the history table, one runs the scripts),
     * so a pool must allow at least {@value #MIGRATION_CONNECTIONS}.
     *
     * @throws IllegalArgumentException if {@code dataSource} is a HikariCP pool too small to migrate
     * @throws IllegalStateException if a migration fails, an applied one was edited, or none exist
     */
    static SchemaMigrator migrate(DataSource dataSource, String location) {
        Objects.requireNonNull(dataSource, "dataSource");
        if (dataSource instanceof HikariDataSource pool && pool.getMaximumPoolSize() < MIGRATION_CONNECTIONS) {
            // Fail now rather than after the pool's connection timeout, with the reason spelled out.
            throw new IllegalArgumentException("Schema migration needs a pool of at least " + MIGRATION_CONNECTIONS
                    + " connections, got maximumPoolSize=" + pool.getMaximumPoolSize());
        }
        return run(Flyway.configure().dataSource(dataSource), location);
    }

    /**
     * Applies pending migrations over short-lived connections of its own to {@code jdbcUrl}, so a
     * small pool on the same database is never needed for, or blocked by, the migration.
     */
    static SchemaMigrator migrate(String jdbcUrl, String location) {
        Objects.requireNonNull(jdbcUrl, "jdbcUrl");
        // Flyway masks everything after the subprotocol when it quotes this URL in an error.
        return run(Flyway.configure().dataSource(jdbcUrl, null, null), location);
    }

    private static SchemaMigrator run(FluentConfiguration config, String location) {
        Objects.requireNonNull(location, "location");
        Flyway flyway = config.locations(location)
                .table(HISTORY_TABLE)
                .baselineOnMigrate(true)
                .baselineVersion(BASELINE_VERSION)
                .failOnMissingLocations(true)
                .load();
        for (int attempt = 1; ; attempt++) {
            MigrateResult result;
            try {
                result = flyway.migrate();
            } catch (FlywayException e) {
                // Retry only the startup race: an existing but empty history. A real failure either
                // leaves rows behind (a failed migration, or applied ones that no longer validate) or
                // no table at all: fail at once, with Flyway's own message, which names the cause.
                if (attempt >= MAX_ATTEMPTS || !historyIsEmpty(flyway)) {
                    throw new IllegalStateException("Persistence schema migration failed: " + e.getMessage(), e);
                }
                log.warn(
                        "Persistence schema migration failed, retrying attempt={} maxAttempts={} reason={}",
                        attempt,
                        MAX_ATTEMPTS,
                        e.getMessage());
                pauseBeforeRetry(attempt);
                continue;
            }
            return migrated(result, location);
        }
    }

    private static SchemaMigrator migrated(MigrateResult result, String location) {
        // Target is what this run reached; with nothing pending it can be unset, so fall back to where it began.
        String reached = result.targetSchemaVersion != null ? result.targetSchemaVersion : result.initialSchemaVersion;
        if (reached == null || !MigrationVersion.fromVersion(reached).isNewerThan(BASELINE_VERSION)) {
            // Seen when a repackaged jar drops the SQL resources: tables would never be created.
            throw new IllegalStateException("No persistence schema migrations found at " + location);
        }
        MigrationVersion version = MigrationVersion.fromVersion(reached);
        log.info(
                "Persistence schema migrated schemaVersion={} migrationsExecuted={}",
                version,
                result.migrationsExecuted);
        return new SchemaMigrator(version);
    }

    /**
     * True when the history table exists but has no rows: what another process leaves between
     * creating the table and recording its baseline, and the only state in which Flyway fails a
     * concurrent startup. A missing table means this failure had another cause (an unreachable
     * database, a bad location, broken SQL), and so does anything that stops the check itself; both
     * fail at once instead of waiting through the retries.
     */
    private static boolean historyIsEmpty(Flyway flyway) {
        try (Connection conn = flyway.getConfiguration().getDataSource().getConnection()) {
            if (!historyTableExists(conn)) {
                return false;
            }
            try (PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM \"" + HISTORY_TABLE + "\"");
                    ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getLong(1) == 0;
            }
        } catch (SQLException e) {
            log.debug("Could not inspect the schema history, not retrying the migration", e);
            return false;
        }
    }

    /** Asks the driver's metadata, so no database-specific "table not found" error codes are needed. */
    private static boolean historyTableExists(Connection conn) throws SQLException {
        // Flyway creates the table with a quoted lowercase name in the connection's current schema.
        try (ResultSet tables =
                conn.getMetaData().getTables(conn.getCatalog(), conn.getSchema(), HISTORY_TABLE, null)) {
            return tables.next();
        }
    }

    /** Grows with each attempt, with jitter so processes that collided once do not collide again. */
    private static void pauseBeforeRetry(int attempt) {
        long delay = RETRY_BASE_DELAY_MS * attempt + ThreadLocalRandom.current().nextLong(RETRY_BASE_DELAY_MS);
        try {
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting to retry the schema migration", e);
        }
    }

    /**
     * True when no migration has failed and the schema is at or beyond the version applied at
     * startup. Turns false if a newer instance left a failed migration behind, or the database was
     * restored from a backup older than this release's schema.
     */
    boolean isCurrent(Connection conn) throws SQLException {
        MigrationVersion highestApplied = MigrationVersion.EMPTY;
        try (PreparedStatement ps = conn.prepareStatement(HISTORY_QUERY);
                ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                if (!rs.getBoolean("success")) {
                    return false;
                }
                MigrationVersion version = MigrationVersion.fromVersion(rs.getString("version"));
                if (version.isNewerThan(highestApplied)) {
                    highestApplied = version;
                }
            }
        }
        return highestApplied.compareTo(migratedVersion) >= 0;
    }
}
