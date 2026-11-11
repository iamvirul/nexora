package me.virul.nexora.spring.boot.autoconfigure;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import me.virul.nexora.persistence.jdbc.PoolConfig;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code nexora.*} settings for the auto-configured {@link me.virul.nexora.api.NexoraEngine}.
 *
 * <p>Every optional value left unset falls back to the engine builder's own default, so the
 * defaults live in one place: {@link me.virul.nexora.api.NexoraEngine.Builder}. Values are validated by
 * the builder when the engine is created, so an invalid one fails application startup.
 *
 * @param enabled             whether to create the engine at all
 * @param tenantId            binds the engine to one tenant; unset serves every tenant
 * @param defaultTimeout      per-step timeout when a step sets none
 * @param defaultPlanDeadline deadline for a whole execution when the intent sets none
 * @param sagaEnabled         run compensations for completed steps when an execution fails
 * @param webhookSecret       HMAC secret for signing webhook callbacks; never logged
 * @param stepOutputMaxBytes  cap on each recorded step output, 0 records none
 * @param pluginJars          plugin JARs to load and activate at startup, in order
 * @param retry               engine-wide default retry policy
 * @param persistence         the built-in JDBC execution store
 */
@ConfigurationProperties(prefix = NexoraProperties.PREFIX)
public record NexoraProperties(
        @DefaultValue("true") boolean enabled,
        String tenantId,
        Duration defaultTimeout,
        Duration defaultPlanDeadline,
        boolean sagaEnabled,
        String webhookSecret,
        Integer stepOutputMaxBytes,
        List<Path> pluginJars,
        @DefaultValue Retry retry,
        @DefaultValue Persistence persistence) {

    public static final String PREFIX = "nexora";

    private static final String REDACTED = "******";

    public NexoraProperties {
        pluginJars = pluginJars == null ? List.of() : List.copyOf(pluginJars);
    }

    /** Keeps the webhook secret out of logs and failure analysis output. */
    @Override
    public String toString() {
        return "NexoraProperties[enabled=" + enabled
                + ", tenantId=" + tenantId
                + ", defaultTimeout=" + defaultTimeout
                + ", defaultPlanDeadline=" + defaultPlanDeadline
                + ", sagaEnabled=" + sagaEnabled
                + ", webhookSecret=" + (webhookSecret == null ? null : REDACTED)
                + ", stepOutputMaxBytes=" + stepOutputMaxBytes
                + ", pluginJars=" + pluginJars
                + ", retry=" + retry
                + ", persistence=" + persistence + "]";
    }

    /**
     * Exponential backoff with jitter, applied to every step that names no retry policy. Without
     * {@code maxAttempts} steps are not retried; the other values default to those of
     * {@link me.virul.nexora.retry.ExponentialBackoffPolicy.Builder}.
     *
     * @param maxAttempts  total attempts per step, including the first
     * @param initialDelay delay before the first retry
     * @param multiplier   growth factor of the delay, greater than 1
     * @param maxDelay     upper bound of any single delay
     */
    public record Retry(Integer maxAttempts, Duration initialDelay, Double multiplier, Duration maxDelay) {}

    /**
     * The JDBC execution store, created only when {@code jdbcUrl} is set and the application
     * defines no {@link me.virul.nexora.persistence.ExecutionStore} bean of its own. The schema is
     * migrated at startup. H2 and PostgreSQL 15 or later are supported.
     *
     * @param jdbcUrl JDBC URL, credentials included; never logged
     * @param pool    connection pool sizing
     */
    public record Persistence(String jdbcUrl, @DefaultValue Pool pool) {

        @Override
        public String toString() {
            return "Persistence[jdbcUrl=" + (jdbcUrl == null ? null : REDACTED) + ", pool=" + pool + "]";
        }
    }

    /**
     * HikariCP pool settings; unset values take the {@link PoolConfig} defaults.
     *
     * @param maximumPoolSize   largest number of connections, at least 1
     * @param minimumIdle       idle connections kept open, at most {@code maximumPoolSize}
     * @param connectionTimeout wait for a free connection, at least 250ms
     * @param idleTimeout       idle time before a connection is retired, 0 or at least 10s
     */
    public record Pool(Integer maximumPoolSize, Integer minimumIdle, Duration connectionTimeout, Duration idleTimeout) {

        PoolConfig toPoolConfig() {
            return new PoolConfig(
                    maximumPoolSize != null ? maximumPoolSize : PoolConfig.DEFAULT_MAXIMUM_POOL_SIZE,
                    minimumIdle != null ? minimumIdle : PoolConfig.DEFAULT_MINIMUM_IDLE,
                    connectionTimeout != null ? connectionTimeout.toMillis() : PoolConfig.DEFAULT_CONNECTION_TIMEOUT_MS,
                    idleTimeout != null ? idleTimeout.toMillis() : PoolConfig.DEFAULT_IDLE_TIMEOUT_MS);
        }
    }
}
