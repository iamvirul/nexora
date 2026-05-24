package com.nexora.executor;

import com.nexora.core.capability.CapabilityContract;
import com.nexora.event.CapabilityCircuitClosedEvent;
import com.nexora.event.CapabilityCircuitOpenedEvent;
import com.nexora.event.ExecutionEventBus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks per-capability health using a sliding window and a circuit breaker.
 *
 * Unhealthy means the capability has breached one or more of its contract thresholds
 * over the last N calls (CLOSED -> OPEN). The scheduler asks isHealthy() before each invocation;
 * CapabilityInvoker uses the result to decide whether to route to the fallback.
 *
 * Thread-safe: individual CircuitBreaker instances synchronize on themselves.
 */
public final class CapabilityContractMonitor {

    private static final Logger log = LoggerFactory.getLogger(CapabilityContractMonitor.class);
    private static final int MIN_SAMPLES = 5;

    private final ConcurrentHashMap<String, CircuitBreaker> breakers = new ConcurrentHashMap<>();
    private final ExecutionEventBus eventBus;
    private final com.nexora.spi.CapabilityRegistry registry;

    public CapabilityContractMonitor(ExecutionEventBus eventBus, com.nexora.spi.CapabilityRegistry registry) {
        this.eventBus = java.util.Objects.requireNonNull(eventBus, "eventBus must not be null");
        this.registry = registry;
    }

    public CapabilityContractMonitor(ExecutionEventBus eventBus) {
        this(eventBus, null);
    }

    // Default constructor for tests that don't need events
    public CapabilityContractMonitor() {
        this.eventBus = new ExecutionEventBus() {
            @Override public <E extends com.nexora.event.ExecutionEvent> void publish(E event) {}
            @Override public <E extends com.nexora.event.ExecutionEvent> com.nexora.event.Subscription subscribe(Class<E> eventType, com.nexora.event.EventHandler<E> handler) { return () -> {}; }
        };
        this.registry = null;
    }

    public void recordSuccess(String capabilityId, Duration latency) {
        com.nexora.event.ExecutionEvent event = breaker(capabilityId).recordSuccess(capabilityId, latency.toNanos());
        if (event != null) eventBus.publish(event);
    }

    public void recordFailure(String capabilityId, Duration latency) {
        com.nexora.event.ExecutionEvent event = breaker(capabilityId).recordFailure(capabilityId, latency.toNanos());
        if (event != null) eventBus.publish(event);
    }

    public boolean isHealthy(String capabilityId, CapabilityContract contract) {
        CircuitBreaker.HealthCheckResult result = breaker(capabilityId).isHealthy(capabilityId, contract);
        if (result.eventToPublish() != null) eventBus.publish(result.eventToPublish());
        return result.healthy();
    }

    public HealthSnapshot snapshot(String capabilityId) {
        CircuitBreaker b = breakers.get(capabilityId);
        if (b == null) return new HealthSnapshot(capabilityId, CircuitState.CLOSED, 0, 0.0, Duration.ZERO);
        return b.snapshot(capabilityId);
    }

    private CircuitBreaker breaker(String capabilityId) {
        return breakers.computeIfAbsent(capabilityId, k -> {
            int windowSize = CapabilityContract.DEFAULT_WINDOW;
            if (registry != null) {
                java.util.Optional<com.nexora.spi.CapabilityDescriptor> descriptor = registry.findDescriptor(k);
                if (descriptor.isPresent() && descriptor.get().contract() != null) {
                    windowSize = descriptor.get().contract().slidingWindowSize();
                }
            }
            return new CircuitBreaker(windowSize);
        });
    }

    public enum CircuitState {
        CLOSED, OPEN, HALF_OPEN
    }

    public record HealthSnapshot(String capabilityId, CircuitState state, int sampleCount, double errorRate, Duration p99Latency) {}

    private static final class CircuitBreaker {
        private final int capacity;
        private final LinkedList<Long> latenciesNanos = new LinkedList<>();
        private final LinkedList<Boolean> outcomes = new LinkedList<>();
        
        private CircuitState state = CircuitState.CLOSED;
        private Instant openedAt;
        private Instant lastProbe;

        CircuitBreaker(int capacity) {
            this.capacity = capacity;
        }

        record HealthCheckResult(boolean healthy, com.nexora.event.ExecutionEvent eventToPublish) {}

        synchronized com.nexora.event.ExecutionEvent recordSuccess(String capabilityId, long latencyNanos) {
            com.nexora.event.ExecutionEvent toPublish = null;
            if (state == CircuitState.HALF_OPEN) {
                state = CircuitState.CLOSED;
                latenciesNanos.clear();
                outcomes.clear();
                toPublish = new CapabilityCircuitClosedEvent(capabilityId, Instant.now());
                log.info("Circuit breaker for capability {} transitioned to CLOSED", capabilityId);
            }
            record(latencyNanos, true);
            return toPublish;
        }

        synchronized com.nexora.event.ExecutionEvent recordFailure(String capabilityId, long latencyNanos) {
            com.nexora.event.ExecutionEvent toPublish = null;
            if (state == CircuitState.HALF_OPEN) {
                state = CircuitState.OPEN;
                openedAt = Instant.now();
                toPublish = new CapabilityCircuitOpenedEvent(capabilityId, openedAt);
                log.warn("Circuit breaker for capability {} transitioned to OPEN (probe failed)", capabilityId);
            }
            record(latencyNanos, false);
            return toPublish;
        }

        private void record(long latencyNanos, boolean success) {
            if (latenciesNanos.size() >= capacity) {
                latenciesNanos.removeFirst();
                outcomes.removeFirst();
            }
            latenciesNanos.addLast(latencyNanos);
            outcomes.addLast(success);
        }

        synchronized HealthCheckResult isHealthy(String capabilityId, CapabilityContract contract) {
            Instant now = Instant.now();

            if (state == CircuitState.OPEN) {
                if (now.isAfter(openedAt.plus(contract.openDuration()))) {
                    state = CircuitState.HALF_OPEN;
                    lastProbe = now;
                    log.info("Circuit breaker for capability {} transitioned to HALF_OPEN (sending probe)", capabilityId);
                    return new HealthCheckResult(true, null);
                }
                return new HealthCheckResult(false, null);
            }

            if (state == CircuitState.HALF_OPEN) {
                if (lastProbe != null && now.isAfter(lastProbe.plus(contract.probeInterval()))) {
                    lastProbe = now;
                    return new HealthCheckResult(true, null);
                }
                return new HealthCheckResult(false, null);
            }

            // state == CLOSED
            if (count() < MIN_SAMPLES) return new HealthCheckResult(true, null);

            if (contract.hasErrorRateSla()) {
                double errRate = errorRate();
                if (errRate > contract.maxErrorRate()) {
                    log.warn("Capability {} breached error rate contract: {}% > {}%",
                            capabilityId,
                            String.format(Locale.ROOT, "%.1f", errRate * 100),
                            String.format(Locale.ROOT, "%.1f", contract.maxErrorRate() * 100));
                    return new HealthCheckResult(false, transitionToOpen(capabilityId, now));
                }
            }

            if (contract.hasLatencySla()) {
                Duration p99 = p99Latency();
                if (p99.compareTo(contract.expectedP99Latency()) > 0) {
                    log.warn("Capability {} breached p99 latency contract: {}ms > {}ms",
                            capabilityId, p99.toMillis(), contract.expectedP99Latency().toMillis());
                    return new HealthCheckResult(false, transitionToOpen(capabilityId, now));
                }
            }

            return new HealthCheckResult(true, null);
        }

        private com.nexora.event.ExecutionEvent transitionToOpen(String capabilityId, Instant now) {
            state = CircuitState.OPEN;
            openedAt = now;
            log.warn("Circuit breaker for capability {} transitioned to OPEN", capabilityId);
            return new CapabilityCircuitOpenedEvent(capabilityId, now);
        }

        synchronized HealthSnapshot snapshot(String capabilityId) {
            return new HealthSnapshot(capabilityId, state, count(), errorRate(), p99Latency());
        }

        synchronized CircuitState state() { return state; }
        
        synchronized int count() { return latenciesNanos.size(); }

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
