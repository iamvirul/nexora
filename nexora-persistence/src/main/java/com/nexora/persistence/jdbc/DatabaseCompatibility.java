package com.nexora.persistence.jdbc;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import javax.sql.DataSource;

/**
 * Rejects databases the store's SQL cannot run on, before anything is migrated or written.
 *
 * <p>Upserts use standard {@code MERGE ... USING}, which PostgreSQL only gained in 15. On an older
 * server the schema migrates and health checks pass, yet every upsert fails, and execution writes
 * are logged rather than thrown, so the store would lose data while looking healthy.
 */
final class DatabaseCompatibility {

    static final String POSTGRESQL = "PostgreSQL";
    static final int MIN_POSTGRESQL_MAJOR = 15;

    private DatabaseCompatibility() {}

    /** @throws IllegalStateException if the database is unsupported or its metadata cannot be read */
    static void require(DataSource dataSource) {
        try (Connection conn = dataSource.getConnection()) {
            DatabaseMetaData meta = conn.getMetaData();
            require(meta.getDatabaseProductName(), meta.getDatabaseMajorVersion(), meta.getDatabaseProductVersion());
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to read persistence database metadata", e);
        }
    }

    static void require(String productName, int majorVersion, String productVersion) {
        if (POSTGRESQL.equalsIgnoreCase(productName) && majorVersion < MIN_POSTGRESQL_MAJOR) {
            throw new IllegalStateException("PostgreSQL " + MIN_POSTGRESQL_MAJOR + " or later is required, found "
                    + productVersion + ": the store's upserts use MERGE, which older versions do not support");
        }
    }
}
