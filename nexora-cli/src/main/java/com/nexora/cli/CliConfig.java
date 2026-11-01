package com.nexora.cli;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.nexora.api.ratelimit.RateLimitConfig;
import com.nexora.core.plan.StepCondition;
import com.nexora.core.plan.condition.And;
import com.nexora.core.plan.condition.ContextValueEquals;
import com.nexora.core.plan.condition.Not;
import com.nexora.core.plan.condition.Or;
import com.nexora.core.plan.condition.StepOutputEquals;
import com.nexora.core.plan.condition.StepOutputPresent;
import com.nexora.persistence.StepOutput;
import com.nexora.persistence.jdbc.PoolConfig;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** Deserialize from nexora.json in the working directory, or from --config. */
public class CliConfig {

    public List<StepConfig> steps = List.of();
    public RetryConfig retry = new RetryConfig();
    public OtelConfig otel = new OtelConfig();
    public String webhookSecret;
    /** File path for the H2 embedded store (e.g. "./nexora-data"). Omit for no persistence. */
    public String executionStore;

    public PersistenceConfig persistence = new PersistenceConfig();

    public ObserveConfig observe = new ObserveConfig();

    public static class StepConfig {
        public String id;
        public String capabilityId;
        public String matchesGoalContains; // simple: matches if goal contains this string
        public ConditionConfig condition;
    }

    public static class RetryConfig {
        public int maxAttempts = 3;
        public long initialDelayMs = 200;
        public double multiplier = 2.0;
        public long maxDelayMs = 10_000;
    }

    /** OTLP endpoint, e.g. "http://localhost:4318". Falls back to OTEL_EXPORTER_OTLP_ENDPOINT when unset. */
    public static class OtelConfig {
        public String endpoint;
    }

    /** Settings for the store at {@code executionStore}. */
    public static class PersistenceConfig {
        public PoolSettings pool = new PoolSettings();
        /**
         * Cap on each recorded step output in UTF-8 JSON bytes; larger outputs keep a truncated
         * preview. 0 records none. Unset uses {@link StepOutput#DEFAULT_MAX_BYTES}.
         */
        public Integer stepOutputMaxBytes;
    }

    /** Settings for {@code nexora observe}. */
    public static class ObserveConfig {
        public RateLimitSettings rateLimit = new RateLimitSettings();
    }

    /**
     * Keys under {@code observe.rateLimit}. Unset keys take the {@link RateLimitConfig} defaults: on,
     * 60 requests a minute per client, 10 of them to {@code POST /api/execute}.
     */
    public static class RateLimitSettings {
        /** false turns the built-in limits off entirely, for a reverse proxy that limits instead. */
        public Boolean enabled;

        public Integer globalRpm;
        public Integer executeRpm;
        /** IPs of reverse proxies whose X-Forwarded-For is trusted to name the client. */
        public List<String> trustedProxies;

        /** @throws IllegalArgumentException if a limit is below 1 or a proxy is not an IP address */
        public RateLimitConfig toRateLimitConfig() {
            return new RateLimitConfig(
                    enabled == null || enabled,
                    globalRpm != null ? globalRpm : RateLimitConfig.DEFAULT_GLOBAL_RPM,
                    executeRpm != null ? executeRpm : RateLimitConfig.DEFAULT_EXECUTE_RPM,
                    trustedProxies == null ? Set.of() : Set.copyOf(trustedProxies));
        }
    }

    /** {@code observe.rateLimit}, tolerating an explicit {@code null} for either section. */
    RateLimitConfig rateLimitConfig() {
        if (observe == null || observe.rateLimit == null) {
            return RateLimitConfig.defaults();
        }
        return observe.rateLimit.toRateLimitConfig();
    }

    /** Connection pool keys under {@code persistence.pool}. Unset keys take the {@link PoolConfig} defaults. */
    public static class PoolSettings {
        public Integer maximumPoolSize;
        public Integer minimumIdle;
        public Long connectionTimeoutMs;
        public Long idleTimeoutMs;

        /** @throws IllegalArgumentException if a value is out of range */
        public PoolConfig toPoolConfig() {
            int maxSize = maximumPoolSize != null ? maximumPoolSize : PoolConfig.DEFAULT_MAXIMUM_POOL_SIZE;
            // A small explicit pool must not trip over the default idle floor, so cap the default to fit.
            int minIdle = minimumIdle != null ? minimumIdle : Math.min(PoolConfig.DEFAULT_MINIMUM_IDLE, maxSize);
            return new PoolConfig(
                    maxSize,
                    minIdle,
                    connectionTimeoutMs != null ? connectionTimeoutMs : PoolConfig.DEFAULT_CONNECTION_TIMEOUT_MS,
                    idleTimeoutMs != null ? idleTimeoutMs : PoolConfig.DEFAULT_IDLE_TIMEOUT_MS);
        }
    }

    /** {@code persistence.stepOutputMaxBytes}, or the default when it or its section is unset. */
    int stepOutputMaxBytes() {
        if (persistence == null || persistence.stepOutputMaxBytes == null) {
            return StepOutput.DEFAULT_MAX_BYTES;
        }
        return persistence.stepOutputMaxBytes;
    }

    /** Pool settings from {@code persistence.pool}, tolerating an explicit {@code null} for either section. */
    PoolConfig poolConfig() {
        if (persistence == null || persistence.pool == null) {
            return new PoolSettings().toPoolConfig();
        }
        return persistence.pool.toPoolConfig();
    }

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
    @JsonSubTypes({
        @JsonSubTypes.Type(value = ContextValueEqualsConfig.class, name = "contextValueEquals"),
        @JsonSubTypes.Type(value = StepOutputEqualsConfig.class, name = "stepOutputEquals"),
        @JsonSubTypes.Type(value = StepOutputPresentConfig.class, name = "stepOutputPresent"),
        @JsonSubTypes.Type(value = AndConfig.class, name = "and"),
        @JsonSubTypes.Type(value = OrConfig.class, name = "or"),
        @JsonSubTypes.Type(value = NotConfig.class, name = "not")
    })
    public interface ConditionConfig {
        StepCondition toStepCondition();
    }

    public static class ContextValueEqualsConfig implements ConditionConfig {
        public String key;
        public Object value;

        @Override
        public StepCondition toStepCondition() {
            return new ContextValueEquals(key, value);
        }
    }

    public static class StepOutputEqualsConfig implements ConditionConfig {
        public String stepId;
        public String field;
        public Object value;

        @Override
        public StepCondition toStepCondition() {
            return new StepOutputEquals(stepId, field, value);
        }
    }

    public static class StepOutputPresentConfig implements ConditionConfig {
        public String stepId;
        public String field;

        @Override
        public StepCondition toStepCondition() {
            return new StepOutputPresent(stepId, field);
        }
    }

    public static class AndConfig implements ConditionConfig {
        public List<ConditionConfig> conditions;

        @Override
        public StepCondition toStepCondition() {
            if (conditions == null) {
                throw new IllegalStateException("AndConfig: 'conditions' list must not be null");
            }
            if (conditions.stream().anyMatch(c -> c == null)) {
                throw new IllegalStateException("AndConfig: 'conditions' list must not contain null elements");
            }
            return new And(
                    conditions.stream().map(ConditionConfig::toStepCondition).collect(Collectors.toList()));
        }
    }

    public static class OrConfig implements ConditionConfig {
        public List<ConditionConfig> conditions;

        @Override
        public StepCondition toStepCondition() {
            if (conditions == null) {
                throw new IllegalStateException("OrConfig: 'conditions' list must not be null");
            }
            if (conditions.stream().anyMatch(c -> c == null)) {
                throw new IllegalStateException("OrConfig: 'conditions' list must not contain null elements");
            }
            return new Or(
                    conditions.stream().map(ConditionConfig::toStepCondition).collect(Collectors.toList()));
        }
    }

    public static class NotConfig implements ConditionConfig {
        public ConditionConfig condition;

        @Override
        public StepCondition toStepCondition() {
            if (condition == null) {
                throw new IllegalStateException("NotConfig: 'condition' must not be null");
            }
            return new Not(condition.toStepCondition());
        }
    }
}
