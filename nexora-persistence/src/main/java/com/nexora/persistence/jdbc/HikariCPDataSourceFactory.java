package com.nexora.persistence.jdbc;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.util.Objects;

/** Builds the HikariCP pool backing {@link JdbcExecutionStore}. */
public final class HikariCPDataSourceFactory {

    static final String POOL_NAME = "nexora-persistence";

    private HikariCPDataSourceFactory() {}

    /**
     * Opens a pool against {@code jdbcUrl}. Fails fast: the first connection is established before
     * this returns, so an unreachable database surfaces at startup rather than on the first write.
     * The caller owns the returned pool and must close it.
     *
     * @throws IllegalStateException if the pool cannot open its first connection
     */
    public static HikariDataSource create(String jdbcUrl, PoolConfig poolConfig) {
        Objects.requireNonNull(jdbcUrl, "jdbcUrl");
        Objects.requireNonNull(poolConfig, "poolConfig");
        HikariConfig hikari = new HikariConfig();
        hikari.setPoolName(POOL_NAME);
        hikari.setJdbcUrl(jdbcUrl);
        hikari.setMaximumPoolSize(poolConfig.maximumPoolSize());
        hikari.setMinimumIdle(poolConfig.minimumIdle());
        hikari.setConnectionTimeout(poolConfig.connectionTimeoutMs());
        hikari.setIdleTimeout(poolConfig.idleTimeoutMs());
        try {
            return new HikariDataSource(hikari);
        } catch (RuntimeException e) {
            throw new IllegalStateException("Failed to connect to persistence store: " + redact(jdbcUrl), e);
        }
    }

    /** Drops URL parameters, which is where drivers accept inline credentials (user=, password=). */
    static String redact(String jdbcUrl) {
        int params = jdbcUrl.length();
        for (char separator : new char[] {';', '?'}) {
            int index = jdbcUrl.indexOf(separator);
            if (index >= 0) {
                params = Math.min(params, index);
            }
        }
        return jdbcUrl.substring(0, params);
    }
}
