package com.nexora.core.capability;

import java.time.Duration;

/**
 * Declares the expected operational behaviour of a capability.
 *
 * The engine monitors live call metrics against these thresholds. When a capability
 * breaches its contract, the engine automatically routes calls to the declared
 * fallback capability until the primary recovers.
 *
 * All thresholds are optional. Omit what you do not want enforced.
 */
public record CapabilityContract(
        Duration expectedP99Latency,
        double maxErrorRate,
        int slidingWindowSize,
        String fallbackCapabilityId,
        Duration openDuration,
        Duration probeInterval
) {
    public static final int DEFAULT_WINDOW = 20;

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

    /** No contract enforced; monitoring still records metrics. */
    public static CapabilityContract none() {
        return new CapabilityContract(null, -1.0, DEFAULT_WINDOW, null, Duration.ofSeconds(30), Duration.ofSeconds(10));
    }

    public static Builder builder() {
        return new Builder();
    }

    public boolean hasLatencySla() { return expectedP99Latency != null; }
    public boolean hasErrorRateSla() { return maxErrorRate >= 0.0; }
    public boolean hasFallback() { return fallbackCapabilityId != null; }

    public static final class Builder {
        private Duration expectedP99Latency;
        private double maxErrorRate = -1.0;
        private int slidingWindowSize = DEFAULT_WINDOW;
        private String fallbackCapabilityId;
        private Duration openDuration = Duration.ofSeconds(30);
        private Duration probeInterval = Duration.ofSeconds(10);

        public Builder p99Latency(Duration latency) { this.expectedP99Latency = latency; return this; }
        public Builder maxErrorRate(double rate) { this.maxErrorRate = rate; return this; }
        public Builder windowSize(int size) { this.slidingWindowSize = size; return this; }
        public Builder fallback(String capabilityId) { this.fallbackCapabilityId = capabilityId; return this; }
        public Builder openDuration(Duration duration) { this.openDuration = duration; return this; }
        public Builder probeInterval(Duration interval) { this.probeInterval = interval; return this; }

        public CapabilityContract build() {
            return new CapabilityContract(expectedP99Latency, maxErrorRate, slidingWindowSize, fallbackCapabilityId, openDuration, probeInterval);
        }
    }
}
