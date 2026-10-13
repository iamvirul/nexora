package com.nexora.persistence.jdbc;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;

class PoolConfigTest {

    @Test
    void defaultsAreValid() {
        PoolConfig defaults = PoolConfig.defaults();

        assertEquals(PoolConfig.DEFAULT_MAXIMUM_POOL_SIZE, defaults.maximumPoolSize());
        assertEquals(PoolConfig.DEFAULT_MINIMUM_IDLE, defaults.minimumIdle());
    }

    @Test
    void acceptsZeroIdleTimeoutMeaningNeverRetire() {
        assertDoesNotThrow(() -> new PoolConfig(2, 0, 1_000, 0));
    }

    @Test
    void rejectsEmptyPool() {
        assertThrows(IllegalArgumentException.class, () -> new PoolConfig(0, 0, 1_000, 0));
    }

    @Test
    void rejectsMinimumIdleAboveMaximumPoolSize() {
        assertThrows(IllegalArgumentException.class, () -> new PoolConfig(2, 3, 1_000, 0));
    }

    @Test
    void rejectsNegativeMinimumIdle() {
        assertThrows(IllegalArgumentException.class, () -> new PoolConfig(2, -1, 1_000, 0));
    }

    @Test
    void rejectsConnectionTimeoutBelowHikariFloor() {
        assertThrows(IllegalArgumentException.class, () -> new PoolConfig(2, 1, 100, 0));
    }

    @Test
    void rejectsIdleTimeoutBelowHikariFloor() {
        assertThrows(IllegalArgumentException.class, () -> new PoolConfig(2, 1, 1_000, 5_000));
    }

    @Test
    void factoryAppliesEverySetting() {
        PoolConfig config = new PoolConfig(4, 1, 1_500, 20_000);

        try (HikariDataSource pool = HikariCPDataSourceFactory.create("jdbc:h2:mem:cfg_" + System.nanoTime(), config)) {
            assertEquals(HikariCPDataSourceFactory.POOL_NAME, pool.getPoolName());
            assertEquals(4, pool.getMaximumPoolSize());
            assertEquals(1, pool.getMinimumIdle());
            assertEquals(1_500, pool.getConnectionTimeout());
            assertEquals(20_000, pool.getIdleTimeout());
        }
    }

    @Test
    void redactKeepsOnlyTheSubprotocol() {
        assertEquals("jdbc:h2", HikariCPDataSourceFactory.redact("jdbc:h2:./data;PASSWORD=x;AUTO_SERVER=TRUE"));
        assertEquals("jdbc:postgresql", HikariCPDataSourceFactory.redact("jdbc:postgresql://db/nexora?password=x"));
        assertEquals("jdbc:oracle", HikariCPDataSourceFactory.redact("jdbc:oracle:thin:scott/tiger@//db:1521/svc"));
        assertEquals("jdbc:mysql", HikariCPDataSourceFactory.redact("jdbc:mysql://scott:tiger@db:3306/nexora"));
        assertEquals("jdbc", HikariCPDataSourceFactory.redact("not-a-jdbc-url:scott:tiger"));
    }

    @Test
    void connectionErrorNeverContainsCredentialsBeforeTheHost() {
        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> HikariCPDataSourceFactory.create(
                        "jdbc:nexora-no-such-driver:thin:scott/tiger@//db:1521/svc", PoolConfig.singleConnection()));

        assertFalse(error.getMessage().contains("tiger"));
    }

    @Test
    void namedInMemoryH2GetsAnUnboundedLifetime() {
        assertEquals(
                "jdbc:h2:mem:nexora;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                HikariCPDataSourceFactory.withInMemoryLifetime("jdbc:h2:mem:nexora;MODE=PostgreSQL"));
        assertEquals(
                "jdbc:h2:mem:nexora;db_close_delay=5",
                HikariCPDataSourceFactory.withInMemoryLifetime("jdbc:h2:mem:nexora;db_close_delay=5"));
        assertEquals("jdbc:h2:./data", HikariCPDataSourceFactory.withInMemoryLifetime("jdbc:h2:./data"));
    }
}
