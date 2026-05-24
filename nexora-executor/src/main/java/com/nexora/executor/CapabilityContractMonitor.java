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

    public CapabilityContractMonitor(ExecutionEventBus eventBus) {
        this.eventBus = java.util.Objects.requireNonNull(eventBus, "eventBus must not be null");
    }

    // Default constructor for tests that don't need events
    public CapabilityContractMonitor() {
        this.eventBus = new ExecutionEventBus() {
            @Override public <E extends com.nexora.event.ExecutionEvent> void publish(E event) {}
            @Override public <E extends com.nexora.event.ExecutionEvent> com.nexora.event.Subscription subscribe(Class<E> eventType, com.nexora.event.EventHandler<E> handler) { return () -> {}; }
        };
    }

    public void recordSuccess(String capabilityId, Duration latency) {
        breaker(capabilityId).recordSuccess(capabilityId, latency.toNanos(), eventBus);
    }

    public void recordFailure(String capabilityId, Duration latency) {
        breaker(capabilityId).recordFailure(capabilityId, latency.toNanos(), eventBus);
    }

    public boolean isHealthy(String capabilityId, CapabilityContract contract) {
        return breaker(capabilityId).isHealthy(capabilityId, contract, eventBus);
    }

    public HealthSnapshot snapshot(String capabilityId) {
        CircuitBreaker b = breakers.get(capabilityId);
        if (b == null) return new HealthSnapshot(capabilityId, CircuitState.CLOSED, 0, 0.0, Duration.ZERO);
        return new HealthSnapshot(capabilityId, b.state(), b.count(), b.errorRate(), b.p99Latency());
    }

    private CircuitBreaker breaker(String capabilityId) {
        return breakers.computeIfAbsent(capabilityId, k -> new CircuitBreaker(CapabilityContract.DEFAULT_WINDOW));
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

        synchronized void recordSuccess(String capabilityId, long latencyNanos, ExecutionEventBus eventBus) {
            if (state == CircuitState.HALF_OPEN) {
                state = CircuitState.CLOSED;
                latenciesNanos.clear();
                outcomes.clear();
                eventBus.publish(new CapabilityCircuitClosedEvent(capabilityId, Instant.now()));
                log.info("Circuit breaker for capability {} transitioned to CLOSED", capabilityId);
            }
            record(latencyNanos, true);
        }

        synchronized void recordFailure(String capabilityId, long latencyNanos, ExecutionEventBus eventBus) {
            if (state == CircuitState.HALF_OPEN) {
                state = CircuitState.OPEN;
                openedAt = Instant.now();
                eventBus.publish(new CapabilityCircuitOpenedEvent(capabilityId, openedAt));
                log.warn("Circuit breaker for capability {} transitioned to OPEN (probe failed)", capabilityId);
            }
            record(latencyNanos, false);
        }

        private void record(long latencyNanos, boolean success) {
            if (latenciesNanos.size() >= capacity) {
                latenciesNanos.removeFirst();
                outcomes.removeFirst();
            }
            latenciesNanos.addLast(latencyNanos);
            outcomes.addLast(success);
        }

        synchronized boolean isHealthy(String capabilityId, CapabilityContract contract, ExecutionEventBus eventBus) {
            Instant now = Instant.now();

            if (state == CircuitState.OPEN) {
                if (now.isAfter(openedAt.plus(contract.openDuration()))) {
                    state = CircuitState.HALF_OPEN;
                    lastProbe = now;
                    log.info("Circuit breaker for capability {} transitioned to HALF_OPEN (sending probe)", capabilityId);
                    return true;
                }
                return false;
            }

            if (state == CircuitState.HALF_OPEN) {
                if (lastProbe != null && now.isAfter(lastProbe.plus(contract.probeInterval()))) {
                    lastProbe = now;
                    return true;
                }
                return false;
            }

            // state == CLOSED
            if (count() < MIN_SAMPLES) return true;

            if (contract.hasErrorRateSla()) {
                double errRate = errorRate();
                if (errRate > contract.maxErrorRate()) {
                    log.warn("Capability {} breached error rate contract: {}% > {}%",
                            capabilityId,
                            String.format(Locale.ROOT, "%.1f", errRate * 100),
                            String.format(Locale.ROOT, "%.1f", contract.maxErrorRate() * 100));
                    transitionToOpen(capabilityId, now, eventBus);
                    return false;
                }
            }

            if (contract.hasLatencySla()) {
                Duration p99 = p99Latency();
                if (p99.compareTo(contract.expectedP99Latency()) > 0) {
                    log.warn("Capability {} breached p99 latency contract: {}ms > {}ms",
                            capabilityId, p99.toMillis(), contract.expectedP99Latency().toMillis());
                    transitionToOpen(capabilityId, now, eventBus);
                    return false;
                }
            }

            return true;
        }

        private void transitionToOpen(String capabilityId, Instant now, ExecutionEventBus eventBus) {
            state = CircuitState.OPEN;
            openedAt = now;
            eventBus.publish(new CapabilityCircuitOpenedEvent(capabilityId, now));
            log.warn("Circuit breaker for capability {} transitioned to OPEN", capabilityId);
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
