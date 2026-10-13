package com.nexora.persistence.jdbc;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

class DatabaseCompatibilityTest {

    @Test
    void rejectsPostgreSqlWithoutMerge() {
        IllegalStateException error = assertThrows(
                IllegalStateException.class, () -> DatabaseCompatibility.require("PostgreSQL", 14, "14.24"));

        assertTrue(error.getMessage().contains("PostgreSQL 15 or later is required, found 14.24"), error.getMessage());
    }

    @Test
    void acceptsPostgreSqlFromFifteen() {
        assertDoesNotThrow(() -> DatabaseCompatibility.require("PostgreSQL", 15, "15.0"));
        assertDoesNotThrow(() -> DatabaseCompatibility.require("PostgreSQL", 18, "18.6"));
    }

    @Test
    void acceptsH2() {
        JdbcDataSource h2 = new JdbcDataSource();
        h2.setURL("jdbc:h2:mem:compat_" + System.nanoTime());

        assertDoesNotThrow(() -> DatabaseCompatibility.require(h2));
    }

    @Test
    void unreadableMetadataFailsConstruction() {
        JdbcDataSource unreachable = new JdbcDataSource();
        unreachable.setURL("jdbc:h2:mem:absent_" + System.nanoTime() + ";IFEXISTS=TRUE");

        assertThrows(IllegalStateException.class, () -> DatabaseCompatibility.require(unreachable));
    }
}
