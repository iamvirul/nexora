package me.virul.nexora.core.capability;

import java.time.Duration;

/**
 * Declares the expected operational behaviour of a capability.
 *
 * <p>The engine monitors live call metrics against these thresholds. When a capability
 * breaches its contract, the engine automatically routes calls to the declared
 * fallback capability until the primary recovers.
 *
 * <p>All thresholds are optional. Omit what you do not want enforced.
 *
 * <p>The breaker behind a contract has three states. While closed, every call goes to the
 * capability and its outcome is recorded in a sliding window. A breach of the error rate or p99
 * latency threshold opens it: calls go to the fallback for {@link #openDuration()}. After that it is
 * half-open and lets one probe call through every {@link #probeInterval()}; a successful probe closes
 * it, a failed one opens it again.
 *
 * @param expectedP99Latency   the p99 latency the capability should stay under, over the sliding
 *                             window; also the default timeout of a step that sets none. {@code null}
 *                             means no latency threshold
 * @param maxErrorRate         the highest tolerated share of failed calls in the sliding window, from
 *                             {@code 0.0} to {@code 1.0}; a negative value means no error rate threshold.
 *                             Not range-checked
 * @param slidingWindowSize    the number of most recent calls the error rate and p99 latency are
 *                             computed over; at least 5
 * @param fallbackCapabilityId the capability that serves calls while the breaker is open, or
 *                             {@code null} for none
 * @param openDuration         how long the breaker stays open before it lets a probe through; must
 *                             not be negative
 * @param probeInterval        the minimum time between probe calls while half-open; must be positive
 */
public record CapabilityContract(
        Duration expectedP99Latency,
        double maxErrorRate,
        int slidingWindowSize,
        String fallbackCapabilityId,
        Duration openDuration,
        Duration probeInterval) {

    /** The sliding window size used when none is given: the last 20 calls. */
    public static final int DEFAULT_WINDOW = 20;

    /**
     * Validates the contract.
     *
     * @throws IllegalArgumentException if {@code slidingWindowSize} is under 5, {@code openDuration}
     *                                  is negative, or {@code probeInterval} is zero or negative
     */
    public CapabilityContract {
        if (slidingWindowSize < 5) {
            throw new IllegalArgumentException("slidingWindowSize must be at least 5, got: " + slidingWindowSize);
        }
        if (openDuration != null && openDuration.isNegative()) {
            throw new IllegalArgumentException("openDuration must be non-negative");
        }
        if (probeInterval != null && (probeInterval.isNegative() || probeInterval.isZero())) {
            throw new IllegalArgumentException("probeInterval must be positive");
        }
    }

    /**
     * A contract that enforces nothing. Monitoring still records metrics.
     *
     * @return a contract with no latency or error rate threshold and no fallback
     */
    public static CapabilityContract none() {
        return new CapabilityContract(null, -1.0, DEFAULT_WINDOW, null, Duration.ofSeconds(30), Duration.ofSeconds(10));
    }

    /**
     * Starts a contract with no thresholds, a window of {@link #DEFAULT_WINDOW} calls, a 30 second
     * open duration and a 10 second probe interval.
     *
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Whether this contract enforces a p99 latency threshold.
     *
     * @return {@code true} when {@link #expectedP99Latency()} is set
     */
    public boolean hasLatencySla() {
        return expectedP99Latency != null;
    }

    /**
     * Whether this contract enforces an error rate threshold.
     *
     * @return {@code true} when {@link #maxErrorRate()} is zero or more
     */
    public boolean hasErrorRateSla() {
        return maxErrorRate >= 0.0;
    }

    /**
     * Whether this contract names a fallback capability.
     *
     * @return {@code true} when {@link #fallbackCapabilityId()} is set
     */
    public boolean hasFallback() {
        return fallbackCapabilityId != null;
    }

    /**
     * Builds a {@link CapabilityContract}. Thresholds left unset are not enforced. Not thread-safe.
     */
    public static final class Builder {
        private Duration expectedP99Latency;
        private double maxErrorRate = -1.0;
        private int slidingWindowSize = DEFAULT_WINDOW;
        private String fallbackCapabilityId;
        private Duration openDuration = Duration.ofSeconds(30);
        private Duration probeInterval = Duration.ofSeconds(10);

        /** Creates a builder with the defaults described at {@link CapabilityContract#builder()}. */
        public Builder() {}

        /**
         * Sets the p99 latency threshold.
         *
         * @param latency the latency the p99 must stay under, or {@code null} for none
         * @return this builder
         */
        public Builder p99Latency(Duration latency) {
            this.expectedP99Latency = latency;
            return this;
        }

        /**
         * Sets the error rate threshold.
         *
         * @param rate the highest tolerated share of failed calls, from {@code 0.0} to {@code 1.0}, or
         *             a negative value for none
         * @return this builder
         */
        public Builder maxErrorRate(double rate) {
            this.maxErrorRate = rate;
            return this;
        }

        /**
         * Sets the number of recent calls the thresholds are measured over.
         *
         * @param size the window size; at least 5, checked by {@link #build()}
         * @return this builder
         */
        public Builder windowSize(int size) {
            this.slidingWindowSize = size;
            return this;
        }

        /**
         * Sets the capability that serves calls while the breaker is open.
         *
         * @param capabilityId the fallback capability id, or {@code null} for none
         * @return this builder
         */
        public Builder fallback(String capabilityId) {
            this.fallbackCapabilityId = capabilityId;
            return this;
        }

        /**
         * Sets how long the breaker stays open before it probes the capability again.
         *
         * @param duration the open duration; not negative, checked by {@link #build()}
         * @return this builder
         */
        public Builder openDuration(Duration duration) {
            this.openDuration = duration;
            return this;
        }

        /**
         * Sets the minimum time between probe calls while the breaker is half-open.
         *
         * @param interval the probe interval; positive, checked by {@link #build()}
         * @return this builder
         */
        public Builder probeInterval(Duration interval) {
            this.probeInterval = interval;
            return this;
        }

        /**
         * Builds the contract.
         *
         * @return the contract
         * @throws IllegalArgumentException if the window size is under 5, the open duration is negative,
         *                                  or the probe interval is not positive
         */
        public CapabilityContract build() {
            return new CapabilityContract(
                    expectedP99Latency,
                    maxErrorRate,
                    slidingWindowSize,
                    fallbackCapabilityId,
                    openDuration,
                    probeInterval);
        }
    }
}
