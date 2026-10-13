package com.nexora.persistence.jdbc;

import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
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
        try {
            MigrateResult result = flyway.migrate();
            // Target is what this run reached; with nothing pending it can be unset, so fall back to where it began.
            String reached =
                    result.targetSchemaVersion != null ? result.targetSchemaVersion : result.initialSchemaVersion;
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
        } catch (FlywayException e) {
            throw new IllegalStateException("Persistence schema migration failed: " + e.getMessage(), e);
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
