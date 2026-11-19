package me.virul.nexora.executor;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicReferenceArray;
import me.virul.nexora.core.capability.CapabilityContract;
import me.virul.nexora.event.CapabilityCircuitClosedEvent;
import me.virul.nexora.event.CapabilityCircuitOpenedEvent;
import me.virul.nexora.event.EventHandler;
import me.virul.nexora.event.ExecutionEvent;
import me.virul.nexora.event.ExecutionEventBus;
import me.virul.nexora.event.Subscription;
import me.virul.nexora.spi.CapabilityDescriptor;
import me.virul.nexora.spi.CapabilityRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tracks per-capability health using a sliding window and a circuit breaker.
 *
 * <p>Unhealthy means the capability has breached one or more of its contract thresholds over the
 * last N calls (CLOSED to OPEN). {@link CapabilityInvoker} asks {@link #isHealthy} before each call
 * to a capability with a fallback and routes to the fallback while it is unhealthy.
 *
 * <p>Thread-safe without locks, so concurrent calls to one capability do not wait for each other:
 * each call is recorded into a fixed ring of atomic slots, and every breaker state change is a
 * compare-and-set, so exactly one caller opens the circuit, sends a probe or closes it again, and
 * each transition publishes its event once.
 */
public final class CapabilityContractMonitor {

    private static final Logger log = LoggerFactory.getLogger(CapabilityContractMonitor.class);
    private static final int MIN_SAMPLES = 5;

    private final ConcurrentHashMap<String, CircuitBreaker> breakers = new ConcurrentHashMap<>();
    private final ExecutionEventBus eventBus;
    private final CapabilityRegistry registry;

    /**
     * Creates a monitor that sizes each capability's window from its registered contract.
     *
     * @param eventBus receives circuit opened and closed events
     * @param registry where contracts are looked up, or {@code null} for the default window size
     */
    public CapabilityContractMonitor(ExecutionEventBus eventBus, CapabilityRegistry registry) {
        this.eventBus = Objects.requireNonNull(eventBus, "eventBus must not be null");
        this.registry = registry;
    }

    /**
     * Creates a monitor with the default window size for every capability.
     *
     * @param eventBus receives circuit opened and closed events
     */
    public CapabilityContractMonitor(ExecutionEventBus eventBus) {
        this(eventBus, null);
    }

    /** Creates a monitor that publishes no events, with the default window size. */
    public CapabilityContractMonitor() {
        this(new DiscardingEventBus(), null);
    }

    /**
     * Records a successful call. A success while half-open closes the circuit.
     *
     * @param capabilityId the capability
     * @param latency      how long the call took
     */
    public void recordSuccess(String capabilityId, Duration latency) {
        publish(breaker(capabilityId).recordSuccess(capabilityId, latency.toNanos()));
    }

    /**
     * Records a failed call. A failure while half-open opens the circuit again.
     *
     * @param capabilityId the capability
     * @param latency      how long the call took
     */
    public void recordFailure(String capabilityId, Duration latency) {
        publish(breaker(capabilityId).recordFailure(capabilityId, latency.toNanos()));
    }

    /**
     * Whether a call should go to the capability rather than its fallback, opening the circuit when
     * the window breaches the contract.
     *
     * @param capabilityId the capability
     * @param contract     its contract
     * @return {@code true} to call the capability
     */
    public boolean isHealthy(String capabilityId, CapabilityContract contract) {
        CircuitBreaker.HealthCheckResult result = breaker(capabilityId).isHealthy(capabilityId, contract);
        publish(result.eventToPublish());
        return result.healthy();
    }

    /**
     * The breaker state and window statistics of a capability.
     *
     * @param capabilityId the capability
     * @return the snapshot; closed and empty for a capability never called
     */
    public HealthSnapshot snapshot(String capabilityId) {
        CircuitBreaker b = breakers.get(capabilityId);
        if (b == null) return new HealthSnapshot(capabilityId, CircuitState.CLOSED, 0, 0.0, Duration.ZERO);
        return b.snapshot(capabilityId);
    }

    private void publish(ExecutionEvent event) {
        if (event != null) eventBus.publish(event);
    }

    private CircuitBreaker breaker(String capabilityId) {
        CircuitBreaker existing = breakers.get(capabilityId);
        return existing != null ? existing : breakers.computeIfAbsent(capabilityId, this::newBreaker);
    }

    private CircuitBreaker newBreaker(String capabilityId) {
        int windowSize = Optional.ofNullable(registry)
                .flatMap(r -> r.findDescriptor(capabilityId))
                .map(CapabilityDescriptor::contract)
                .map(CapabilityContract::slidingWindowSize)
                .orElse(CapabilityContract.DEFAULT_WINDOW);
        return new CircuitBreaker(windowSize);
    }

    /** The state of a capability's circuit breaker. */
    public enum CircuitState {
        /** Calls go to the capability. */
        CLOSED,
        /** Calls go to the fallback. */
        OPEN,
        /** Calls go to the fallback, except for one probe per probe interval. */
        HALF_OPEN
    }

    /**
     * The breaker state and window statistics of one capability.
     *
     * @param capabilityId the capability
     * @param state        the breaker state
     * @param sampleCount  the number of calls in the window
     * @param errorRate    the share of failed calls in the window
     * @param p99Latency   the p99 latency over the window
     */
    public record HealthSnapshot(
            String capabilityId, CircuitState state, int sampleCount, double errorRate, Duration p99Latency) {}

    private static final class CircuitBreaker {
        private static final HealthCheckResult HEALTHY = new HealthCheckResult(true, null);
        private static final HealthCheckResult UNHEALTHY = new HealthCheckResult(false, null);

        private final int capacity;
        private final AtomicReference<Generation> generation;

        CircuitBreaker(int capacity) {
            this.capacity = capacity;
            this.generation = new AtomicReference<>(Generation.closed(new CallWindow(capacity)));
        }

        record HealthCheckResult(boolean healthy, ExecutionEvent eventToPublish) {}

        /**
         * The breaker state together with the window it applies to, swapped as one value, so no reader
         * can see a circuit that was just closed with the failed window it closed over.
         */
        private record Generation(CircuitState circuit, long openedAtNanos, long lastProbeNanos, CallWindow window) {
            static Generation closed(CallWindow window) {
                return new Generation(CircuitState.CLOSED, 0L, 0L, window);
            }

            Generation opened(long now) {
                return new Generation(CircuitState.OPEN, now, 0L, window);
            }

            Generation probing(long now) {
                return new Generation(CircuitState.HALF_OPEN, openedAtNanos, now, window);
            }
        }

        ExecutionEvent recordSuccess(String capabilityId, long latencyNanos) {
            while (true) {
                Generation current = generation.get();
                if (current.circuit() != CircuitState.HALF_OPEN) {
                    current.window().record(latencyNanos, true);
                    return null;
                }
                Generation closed = Generation.closed(new CallWindow(capacity));
                if (generation.compareAndSet(current, closed)) {
                    closed.window().record(latencyNanos, true);
                    log.info("Circuit breaker for capability {} transitioned to CLOSED", capabilityId);
                    return new CapabilityCircuitClosedEvent(capabilityId, Instant.now());
                }
            }
        }

        ExecutionEvent recordFailure(String capabilityId, long latencyNanos) {
            while (true) {
                Generation current = generation.get();
                if (current.circuit() != CircuitState.HALF_OPEN) {
                    current.window().record(latencyNanos, false);
                    return null;
                }
                Generation reopened = current.opened(System.nanoTime());
                if (generation.compareAndSet(current, reopened)) {
                    reopened.window().record(latencyNanos, false);
                    log.warn("Circuit breaker for capability {} transitioned to OPEN (probe failed)", capabilityId);
                    return new CapabilityCircuitOpenedEvent(capabilityId, Instant.now());
                }
            }
        }

        HealthCheckResult isHealthy(String capabilityId, CapabilityContract contract) {
            long now = System.nanoTime();
            Generation current = generation.get();
            return switch (current.circuit()) {
                case OPEN -> probeAfterOpen(capabilityId, contract, current, now);
                case HALF_OPEN -> probeAgain(contract, current, now);
                case CLOSED -> checkWindow(capabilityId, contract, current, now);
            };
        }

        private HealthCheckResult probeAfterOpen(
                String capabilityId, CapabilityContract contract, Generation current, long now) {
            if (now - current.openedAtNanos() > contract.openDuration().toNanos()
                    && generation.compareAndSet(current, current.probing(now))) {
                log.info("Circuit breaker for capability {} transitioned to HALF_OPEN (sending probe)", capabilityId);
                return HEALTHY;
            }
            return UNHEALTHY;
        }

        private HealthCheckResult probeAgain(CapabilityContract contract, Generation current, long now) {
            if (now - current.lastProbeNanos() > contract.probeInterval().toNanos()
                    && generation.compareAndSet(current, current.probing(now))) {
                return HEALTHY;
            }
            return UNHEALTHY;
        }

        private HealthCheckResult checkWindow(
                String capabilityId, CapabilityContract contract, Generation current, long now) {
            CallWindow calls = current.window();
            if (calls.count() < MIN_SAMPLES) return HEALTHY;

            if (contract.hasErrorRateSla()) {
                double errorRate = calls.errorRate();
                if (errorRate > contract.maxErrorRate()) {
                    log.warn(
                            "Capability {} breached error rate contract: {}% > {}%",
                            capabilityId,
                            String.format(Locale.ROOT, "%.1f", errorRate * 100),
                            String.format(Locale.ROOT, "%.1f", contract.maxErrorRate() * 100));
                    return new HealthCheckResult(false, open(capabilityId, current, now));
                }
            }

            if (contract.hasLatencySla()) {
                Duration p99 = calls.p99Latency();
                if (p99.compareTo(contract.expectedP99Latency()) > 0) {
                    log.warn(
                            "Capability {} breached p99 latency contract: {}ms > {}ms",
                            capabilityId,
                            p99.toMillis(),
                            contract.expectedP99Latency().toMillis());
                    return new HealthCheckResult(false, open(capabilityId, current, now));
                }
            }
            return HEALTHY;
        }

        private ExecutionEvent open(String capabilityId, Generation current, long now) {
            if (!generation.compareAndSet(current, current.opened(now))) {
                return null;
            }
            log.warn("Circuit breaker for capability {} transitioned to OPEN", capabilityId);
            return new CapabilityCircuitOpenedEvent(capabilityId, Instant.now());
        }

        HealthSnapshot snapshot(String capabilityId) {
            Generation current = generation.get();
            CallWindow calls = current.window();
            return new HealthSnapshot(
                    capabilityId, current.circuit(), calls.count(), calls.errorRate(), calls.p99Latency());
        }
    }

    /**
     * The last {@code capacity} calls of one capability, recorded without locks. A call first reserves
     * a sequence number, which picks its slot, then publishes its sample there; a sample never replaces
     * one with a higher sequence number, so a slow call cannot erase a newer one. The failure count
     * follows every accepted replacement, so the error rate needs no scan.
     */
    static final class CallWindow {
        private static final double PERCENTILE = 0.99;
        private static final int MAX_SELECTED = 32;

        private final AtomicReferenceArray<Sample> slots;
        private final AtomicLong reservations = new AtomicLong();
        private final AtomicInteger failures = new AtomicInteger();

        private record Sample(long sequence, long latencyNanos, boolean failed) {}

        CallWindow(int capacity) {
            this.slots = new AtomicReferenceArray<>(capacity);
        }

        void record(long latencyNanos, boolean success) {
            publish(reserve(), latencyNanos, success);
        }

        long reserve() {
            return reservations.getAndIncrement();
        }

        void publish(long sequence, long latencyNanos, boolean success) {
            int slot = (int) (sequence % slots.length());
            Sample sample = new Sample(sequence, Math.max(0L, latencyNanos), !success);
            while (true) {
                Sample previous = slots.get(slot);
                if (previous != null && previous.sequence() > sequence) {
                    return;
                }
                if (slots.compareAndSet(slot, previous, sample)) {
                    int change = failedCount(sample) - failedCount(previous);
                    if (change != 0) failures.addAndGet(change);
                    return;
                }
            }
        }

        int count() {
            return (int) Math.min(reservations.get(), slots.length());
        }

        double errorRate() {
            int count = count();
            if (count == 0) return 0.0;
            return Math.clamp(failures.get(), 0, count) / (double) count;
        }

        Duration p99Latency() {
            long[] latencies = new long[slots.length()];
            int filled = 0;
            for (int i = 0; i < slots.length(); i++) {
                Sample sample = slots.get(i);
                if (sample != null) latencies[filled++] = sample.latencyNanos();
            }
            if (filled == 0) return Duration.ZERO;
            int rank = filled - (Math.max(1, (int) Math.ceil(filled * PERCENTILE)) - 1);
            return Duration.ofNanos(
                    rank <= MAX_SELECTED ? largest(latencies, filled, rank) : sortedPick(latencies, filled, rank));
        }

        private static long largest(long[] latencies, int filled, int rank) {
            long[] top = new long[rank];
            int kept = 0;
            for (int i = 0; i < filled; i++) {
                long latency = latencies[i];
                if (kept < rank) {
                    top[kept++] = latency;
                    insertDescending(top, kept - 1);
                } else if (latency > top[rank - 1]) {
                    top[rank - 1] = latency;
                    insertDescending(top, rank - 1);
                }
            }
            return top[kept - 1];
        }

        private static long sortedPick(long[] latencies, int filled, int rank) {
            Arrays.sort(latencies, 0, filled);
            return latencies[filled - rank];
        }

        private static void insertDescending(long[] values, int index) {
            for (int i = index; i > 0 && values[i] > values[i - 1]; i--) {
                long swap = values[i];
                values[i] = values[i - 1];
                values[i - 1] = swap;
            }
        }

        private static int failedCount(Sample sample) {
            return sample != null && sample.failed() ? 1 : 0;
        }
    }

    private static final class DiscardingEventBus implements ExecutionEventBus {
        @Override
        public <E extends ExecutionEvent> void publish(E event) {}

        @Override
        public <E extends ExecutionEvent> Subscription subscribe(Class<E> eventType, EventHandler<E> handler) {
            return () -> {};
        }
    }
}
