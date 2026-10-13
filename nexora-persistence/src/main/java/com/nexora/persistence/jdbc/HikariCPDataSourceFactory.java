package com.nexora.persistence.jdbc;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.util.Locale;
import java.util.Objects;

/** Builds the HikariCP pool backing {@link JdbcExecutionStore}. */
public final class HikariCPDataSourceFactory {

    static final String POOL_NAME = "nexora-persistence";

    private static final String JDBC_PREFIX = "jdbc:";
    private static final String H2_MEM_PREFIX = "jdbc:h2:mem:";
    private static final String H2_CLOSE_DELAY = "DB_CLOSE_DELAY";

    private HikariCPDataSourceFactory() {}

    /**
     * Opens a pool against {@code jdbcUrl}. Fails fast: the first connection is established before
     * this returns, so an unreachable database surfaces at startup rather than on the first write.
     * The caller owns the returned pool and must close it.
     *
     * <p>A named in-memory H2 URL without {@code DB_CLOSE_DELAY} gets {@code DB_CLOSE_DELAY=-1}, so
     * the database outlives the connection churn of a pool and lasts until the JVM exits.
     *
     * @throws IllegalArgumentException if {@code jdbcUrl} is an unnamed in-memory H2 database
     * @throws IllegalStateException if the pool cannot open its first connection
     */
    public static HikariDataSource create(String jdbcUrl, PoolConfig poolConfig) {
        Objects.requireNonNull(jdbcUrl, "jdbcUrl");
        Objects.requireNonNull(poolConfig, "poolConfig");
        HikariConfig hikari = new HikariConfig();
        hikari.setPoolName(POOL_NAME);
        hikari.setJdbcUrl(withInMemoryLifetime(jdbcUrl));
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

    /**
     * Reduces a URL to {@code jdbc:<subprotocol>}. Drivers accept credentials in parameters
     * ({@code ;password=}, {@code ?password=}) and before the host ({@code user/pass@},
     * {@code user:pass@}), so nothing past the subprotocol is safe to log.
     */
    static String redact(String jdbcUrl) {
        if (!jdbcUrl.regionMatches(true, 0, JDBC_PREFIX, 0, JDBC_PREFIX.length())) {
            return "jdbc";
        }
        int subprotocolEnd = jdbcUrl.indexOf(':', JDBC_PREFIX.length());
        return subprotocolEnd < 0 ? jdbcUrl : jdbcUrl.substring(0, subprotocolEnd);
    }

    /**
     * H2 drops an in-memory database when its last connection closes, and a pool closes connections
     * routinely (idle timeout, max lifetime). Pins named in-memory databases for the JVM's lifetime.
     * An unnamed one is private to a single connection, so every pooled connection would see a
     * different, empty database.
     */
    static String withInMemoryLifetime(String jdbcUrl) {
        if (!jdbcUrl.regionMatches(true, 0, H2_MEM_PREFIX, 0, H2_MEM_PREFIX.length())) {
            return jdbcUrl;
        }
        int paramsStart = jdbcUrl.indexOf(';');
        String name = jdbcUrl.substring(H2_MEM_PREFIX.length(), paramsStart < 0 ? jdbcUrl.length() : paramsStart);
        if (name.isEmpty()) {
            throw new IllegalArgumentException(
                    "Unnamed in-memory H2 databases cannot be pooled; name it, e.g. " + H2_MEM_PREFIX + "nexora");
        }
        boolean hasCloseDelay = paramsStart >= 0
                && jdbcUrl.substring(paramsStart).toUpperCase(Locale.ROOT).contains(";" + H2_CLOSE_DELAY + "=");
        return hasCloseDelay ? jdbcUrl : jdbcUrl + ";" + H2_CLOSE_DELAY + "=-1";
    }
}
