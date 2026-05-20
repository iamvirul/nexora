package com.nexora.executor;

import com.nexora.core.capability.CapabilityContract;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks per-capability health using a sliding window of recent call outcomes.
 *
 * Unhealthy means the capability has breached one or more of its contract thresholds
 * over the last N calls. The scheduler asks isHealthy() before each invocation;
 * CapabilityInvoker uses the result to decide whether to route to the fallback.
 *
 * Thread-safe: individual SlidingWindow instances synchronize on themselves.
 */
public final class CapabilityContractMonitor {

    private static final Logger log = LoggerFactory.getLogger(CapabilityContractMonitor.class);
    private static final int MIN_SAMPLES = 5;

    private final ConcurrentHashMap<String, SlidingWindow> windows = new ConcurrentHashMap<>();

    public void recordSuccess(String capabilityId, Duration latency) {
        window(capabilityId).record(latency.toNanos(), true);
    }

    public void recordFailure(String capabilityId, Duration latency) {
        window(capabilityId).record(latency.toNanos(), false);
    }

    public boolean isHealthy(String capabilityId, CapabilityContract contract) {
        SlidingWindow w = windows.get(capabilityId);
        if (w == null || w.count() < MIN_SAMPLES) return true;

        if (contract.hasErrorRateSla()) {
            double errorRate = w.errorRate();
            if (errorRate > contract.maxErrorRate()) {
                log.warn("Capability {} breached error rate contract: {:.1f}% > {:.1f}%",
                        capabilityId, errorRate * 100, contract.maxErrorRate() * 100);
                return false;
            }
        }

        if (contract.hasLatencySla()) {
            Duration p99 = w.p99Latency();
            if (p99.compareTo(contract.expectedP99Latency()) > 0) {
                log.warn("Capability {} breached p99 latency contract: {}ms > {}ms",
                        capabilityId, p99.toMillis(), contract.expectedP99Latency().toMillis());
                return false;
            }
        }

        return true;
    }

    public HealthSnapshot snapshot(String capabilityId) {
        SlidingWindow w = windows.get(capabilityId);
        if (w == null) return new HealthSnapshot(capabilityId, 0, 0.0, Duration.ZERO);
        return new HealthSnapshot(capabilityId, w.count(), w.errorRate(), w.p99Latency());
    }

    private SlidingWindow window(String capabilityId) {
        return windows.computeIfAbsent(capabilityId, k -> new SlidingWindow(CapabilityContract.DEFAULT_WINDOW));
    }

    public record HealthSnapshot(String capabilityId, int sampleCount, double errorRate, Duration p99Latency) {}

    private static final class SlidingWindow {
        private final int capacity;
        private final LinkedList<Long> latenciesNanos = new LinkedList<>();
        private final LinkedList<Boolean> outcomes = new LinkedList<>();

        SlidingWindow(int capacity) {
            this.capacity = capacity;
        }

        synchronized void record(long latencyNanos, boolean success) {
            if (latenciesNanos.size() >= capacity) {
                latenciesNanos.removeFirst();
                outcomes.removeFirst();
            }
            latenciesNanos.addLast(latencyNanos);
            outcomes.addLast(success);
        }

        synchronized int count() {
            return latenciesNanos.size();
        }

        synchronized double errorRate() {
            if (outcomes.isEmpty()) return 0.0;
            long failures = outcomes.stream().filter(b -> !b).count();
            return (double) failures / outcomes.size();
        }

        synchronized Duration p99Latency() {
            if (latenciesNanos.isEmpty()) return Duration.ZERO;
            List<Long> sorted = new ArrayList<>(latenciesNanos);
            Collections.sort(sorted);
            int idx = (int) Math.ceil(sorted.size() * 0.99) - 1;
            return Duration.ofNanos(sorted.get(Math.max(0, idx)));
        }
    }
}
