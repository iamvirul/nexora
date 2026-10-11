package com.nexora.retry;

import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Exponential backoff with ±25% jitter.
 * Jitter prevents synchronized retry storms when many concurrent executions fail together.
 */
public record ExponentialBackoffPolicy(
        int maxAttempts,
        Duration initialDelay,
        double multiplier,
        Duration maxDelay,
        Set<Class<? extends Throwable>> retryableExceptions)
        implements RetryPolicy {

    public ExponentialBackoffPolicy {
        Objects.requireNonNull(initialDelay, "initialDelay must not be null");
        Objects.requireNonNull(maxDelay, "maxDelay must not be null");
        if (maxAttempts < 1) throw new IllegalArgumentException("maxAttempts must be >= 1");
        if (multiplier <= 1.0) throw new IllegalArgumentException("multiplier must be > 1.0");
        retryableExceptions = retryableExceptions == null ? Set.of() : Set.copyOf(retryableExceptions);
    }

    @Override
    public boolean shouldRetry(int attemptsMade, Throwable cause) {
        if (attemptsMade >= maxAttempts) return false;
        if (cause == null) return false;
        if (retryableExceptions.isEmpty()) return true;
        return retryableExceptions.stream().anyMatch(t -> t.isInstance(cause));
    }

    @Override
    public Duration backoffDelay(int attemptsMade) {
        long baseMs = (long) (initialDelay.toMillis() * Math.pow(multiplier, attemptsMade));
        long cappedMs = Math.min(baseMs, maxDelay.toMillis());
        // ±25% jitter
        double jitterFactor = 1.0 + (ThreadLocalRandom.current().nextDouble() - 0.5) * 0.5;
        return Duration.ofMillis((long) (cappedMs * jitterFactor));
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private int maxAttempts = 3;
        private Duration initialDelay = Duration.ofMillis(200);
        private double multiplier = 2.0;
        private Duration maxDelay = Duration.ofSeconds(10);
        private Set<Class<? extends Throwable>> retryableExceptions = Set.of();

        public Builder maxAttempts(int v) {
            maxAttempts = v;
            return this;
        }

        public Builder initialDelay(Duration v) {
            initialDelay = v;
            return this;
        }

        public Builder multiplier(double v) {
            multiplier = v;
            return this;
        }

        public Builder maxDelay(Duration v) {
            maxDelay = v;
            return this;
        }

        @SafeVarargs
        public final Builder retryOn(Class<? extends Throwable>... types) {
            retryableExceptions = Set.of(types);
            return this;
        }

        public ExponentialBackoffPolicy build() {
            return new ExponentialBackoffPolicy(maxAttempts, initialDelay, multiplier, maxDelay, retryableExceptions);
        }
    }
}
