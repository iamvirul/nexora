package me.virul.nexora.persistence.jdbc;

/**
 * Connection pool sizing and timeouts for {@link JdbcExecutionStore}.
 *
 * <p>{@code probeTimeoutMs} bounds each readiness check ({@link JdbcExecutionStore#isHealthy()},
 * {@link JdbcExecutionStore#isSchemaCurrent()}) separately from {@code connectionTimeoutMs}, which
 * normal work waits for a connection: a saturated pool then reports DOWN quickly without making
 * real work give up sooner.
 *
 * <p>Validated eagerly: HikariCP silently replaces out-of-range timeouts with its own defaults,
 * which would hide a misconfigured {@code nexora.json} until the pool misbehaves under load.
 */
public record PoolConfig(
        int maximumPoolSize, int minimumIdle, long connectionTimeoutMs, long idleTimeoutMs, long probeTimeoutMs) {

    public static final int DEFAULT_MAXIMUM_POOL_SIZE = 10;
    public static final int DEFAULT_MINIMUM_IDLE = 2;
    public static final long DEFAULT_CONNECTION_TIMEOUT_MS = 30_000;
    public static final long DEFAULT_IDLE_TIMEOUT_MS = 600_000;
    // Two checks run per readiness probe; at 1s each they stay inside a 3s Kubernetes probe timeout.
    public static final long DEFAULT_PROBE_TIMEOUT_MS = 1_000;
    public static final long MIN_PROBE_TIMEOUT_MS = 100;

    // HikariCP's own lower bounds; anything smaller is overridden by the pool at startup.
    static final long MIN_CONNECTION_TIMEOUT_MS = 250;
    static final long MIN_IDLE_TIMEOUT_MS = 10_000;

    public PoolConfig {
        if (maximumPoolSize < 1) {
            throw new IllegalArgumentException("maximumPoolSize must be >= 1, got " + maximumPoolSize);
        }
        if (minimumIdle < 0 || minimumIdle > maximumPoolSize) {
            throw new IllegalArgumentException(
                    "minimumIdle must be between 0 and maximumPoolSize (" + maximumPoolSize + "), got " + minimumIdle);
        }
        if (connectionTimeoutMs < MIN_CONNECTION_TIMEOUT_MS) {
            throw new IllegalArgumentException(
                    "connectionTimeoutMs must be >= " + MIN_CONNECTION_TIMEOUT_MS + ", got " + connectionTimeoutMs);
        }
        if (idleTimeoutMs != 0 && idleTimeoutMs < MIN_IDLE_TIMEOUT_MS) {
            throw new IllegalArgumentException(
                    "idleTimeoutMs must be 0 (never retire) or >= " + MIN_IDLE_TIMEOUT_MS + ", got " + idleTimeoutMs);
        }
        if (probeTimeoutMs < MIN_PROBE_TIMEOUT_MS) {
            throw new IllegalArgumentException(
                    "probeTimeoutMs must be >= " + MIN_PROBE_TIMEOUT_MS + ", got " + probeTimeoutMs);
        }
    }

    /** Pool with the default probe timeout, {@value #DEFAULT_PROBE_TIMEOUT_MS}ms. */
    public PoolConfig(int maximumPoolSize, int minimumIdle, long connectionTimeoutMs, long idleTimeoutMs) {
        this(maximumPoolSize, minimumIdle, connectionTimeoutMs, idleTimeoutMs, DEFAULT_PROBE_TIMEOUT_MS);
    }

    public static PoolConfig defaults() {
        return new PoolConfig(
                DEFAULT_MAXIMUM_POOL_SIZE,
                DEFAULT_MINIMUM_IDLE,
                DEFAULT_CONNECTION_TIMEOUT_MS,
                DEFAULT_IDLE_TIMEOUT_MS);
    }

    /** One connection, never retired: matches the store's pre-pool behaviour for embedded H2 and tests. */
    public static PoolConfig singleConnection() {
        return new PoolConfig(1, 1, DEFAULT_CONNECTION_TIMEOUT_MS, DEFAULT_IDLE_TIMEOUT_MS);
    }
}
