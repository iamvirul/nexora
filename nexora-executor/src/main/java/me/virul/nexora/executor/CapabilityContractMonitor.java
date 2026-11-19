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
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.AtomicReference;
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
        private final AtomicReference<State> state = new AtomicReference<>(State.closed());
        private final AtomicReference<Window> window;

        CircuitBreaker(int capacity) {
            this.capacity = capacity;
            this.window = new AtomicReference<>(new Window(capacity));
        }

        record HealthCheckResult(boolean healthy, ExecutionEvent eventToPublish) {}

        private record State(CircuitState circuit, long openedAtNanos, long lastProbeNanos) {
            static State closed() {
                return new State(CircuitState.CLOSED, 0L, 0L);
            }
        }

        ExecutionEvent recordSuccess(String capabilityId, long latencyNanos) {
            ExecutionEvent event = null;
            State current = state.get();
            if (current.circuit() == CircuitState.HALF_OPEN && state.compareAndSet(current, State.closed())) {
                window.set(new Window(capacity));
                event = new CapabilityCircuitClosedEvent(capabilityId, Instant.now());
                log.info("Circuit breaker for capability {} transitioned to CLOSED", capabilityId);
            }
            window.get().record(latencyNanos, true);
            return event;
        }

        ExecutionEvent recordFailure(String capabilityId, long latencyNanos) {
            ExecutionEvent event = null;
            State current = state.get();
            if (current.circuit() == CircuitState.HALF_OPEN
                    && state.compareAndSet(current, new State(CircuitState.OPEN, System.nanoTime(), 0L))) {
                event = new CapabilityCircuitOpenedEvent(capabilityId, Instant.now());
                log.warn("Circuit breaker for capability {} transitioned to OPEN (probe failed)", capabilityId);
            }
            window.get().record(latencyNanos, false);
            return event;
        }

        HealthCheckResult isHealthy(String capabilityId, CapabilityContract contract) {
            long now = System.nanoTime();
            State current = state.get();
            return switch (current.circuit()) {
                case OPEN -> probeAfterOpen(capabilityId, contract, current, now);
                case HALF_OPEN -> probeAgain(contract, current, now);
                case CLOSED -> checkWindow(capabilityId, contract, current, now);
            };
        }

        private HealthCheckResult probeAfterOpen(
                String capabilityId, CapabilityContract contract, State current, long now) {
            if (now - current.openedAtNanos() > contract.openDuration().toNanos()
                    && state.compareAndSet(current, new State(CircuitState.HALF_OPEN, current.openedAtNanos(), now))) {
                log.info("Circuit breaker for capability {} transitioned to HALF_OPEN (sending probe)", capabilityId);
                return HEALTHY;
            }
            return UNHEALTHY;
        }

        private HealthCheckResult probeAgain(CapabilityContract contract, State current, long now) {
            if (now - current.lastProbeNanos() > contract.probeInterval().toNanos()
                    && state.compareAndSet(current, new State(CircuitState.HALF_OPEN, current.openedAtNanos(), now))) {
                return HEALTHY;
            }
            return UNHEALTHY;
        }

        private HealthCheckResult checkWindow(
                String capabilityId, CapabilityContract contract, State current, long now) {
            Window calls = window.get();
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

        private ExecutionEvent open(String capabilityId, State current, long now) {
            if (!state.compareAndSet(current, new State(CircuitState.OPEN, now, 0L))) {
                return null;
            }
            log.warn("Circuit breaker for capability {} transitioned to OPEN", capabilityId);
            return new CapabilityCircuitOpenedEvent(capabilityId, Instant.now());
        }

        HealthSnapshot snapshot(String capabilityId) {
            Window calls = window.get();
            return new HealthSnapshot(
                    capabilityId, state.get().circuit(), calls.count(), calls.errorRate(), calls.p99Latency());
        }
    }

    /**
     * The last {@code capacity} calls of one capability. Each slot packs a latency and an outcome
     * into one long, {@code 0} meaning empty, and the failure count follows every slot overwrite, so
     * recording never locks and the error rate needs no scan.
     */
    private static final class Window {
        private static final long EMPTY = 0L;
        private static final double PERCENTILE = 0.99;
        private static final int MAX_SELECTED = 32;

        private final AtomicLongArray slots;
        private final AtomicLong writes = new AtomicLong();
        private final AtomicInteger failures = new AtomicInteger();

        Window(int capacity) {
            this.slots = new AtomicLongArray(capacity);
        }

        void record(long latencyNanos, boolean success) {
            long encoded = ((Math.max(0L, latencyNanos) << 1) | (success ? 0L : 1L)) + 1L;
            int slot = (int) (writes.getAndIncrement() % slots.length());
            long previous = slots.getAndSet(slot, encoded);
            int change = failed(encoded) - failed(previous);
            if (change != 0) failures.addAndGet(change);
        }

        int count() {
            return (int) Math.min(writes.get(), slots.length());
        }

        double errorRate() {
            int count = count();
            if (count == 0) return 0.0;
            return Math.clamp(failures.get(), 0, count) / (double) count;
        }

        Duration p99Latency() {
            int count = 0;
            for (int i = 0; i < slots.length(); i++) {
                if (slots.get(i) != EMPTY) count++;
            }
            if (count == 0) return Duration.ZERO;
            int rank = count - (Math.max(1, (int) Math.ceil(count * PERCENTILE)) - 1);
            return Duration.ofNanos(rank <= MAX_SELECTED ? largest(rank) : sortedPick(rank));
        }

        private long largest(int rank) {
            long[] top = new long[rank];
            int filled = 0;
            for (int i = 0; i < slots.length(); i++) {
                long slot = slots.get(i);
                if (slot == EMPTY) continue;
                long latency = latency(slot);
                if (filled < rank) {
                    top[filled++] = latency;
                    insertDescending(top, filled - 1);
                } else if (latency > top[rank - 1]) {
                    top[rank - 1] = latency;
                    insertDescending(top, rank - 1);
                }
            }
            return top[Math.max(0, filled - 1)];
        }

        private long sortedPick(int rank) {
            long[] latencies = new long[slots.length()];
            int filled = 0;
            for (int i = 0; i < slots.length(); i++) {
                long slot = slots.get(i);
                if (slot != EMPTY) latencies[filled++] = latency(slot);
            }
            Arrays.sort(latencies, 0, filled);
            return latencies[Math.max(0, filled - rank)];
        }

        private static void insertDescending(long[] values, int index) {
            for (int i = index; i > 0 && values[i] > values[i - 1]; i--) {
                long swap = values[i];
                values[i] = values[i - 1];
                values[i - 1] = swap;
            }
        }

        private static long latency(long slot) {
            return (slot - 1L) >>> 1;
        }

        private static int failed(long slot) {
            return slot == EMPTY ? 0 : (int) ((slot - 1L) & 1L);
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
