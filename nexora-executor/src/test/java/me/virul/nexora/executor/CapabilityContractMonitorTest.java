package me.virul.nexora.executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import me.virul.nexora.core.capability.CapabilityContract;
import me.virul.nexora.event.CapabilityCircuitClosedEvent;
import me.virul.nexora.event.CapabilityCircuitOpenedEvent;
import me.virul.nexora.event.ExecutionEvent;
import me.virul.nexora.registry.DefaultCapabilityRegistry;
import me.virul.nexora.spi.CapabilityDescriptor;
import org.junit.jupiter.api.Test;

class CapabilityContractMonitorTest {

    private static me.virul.nexora.event.ExecutionEventBus capturingBus(List<ExecutionEvent> sink) {
        return new me.virul.nexora.event.ExecutionEventBus() {
            @Override
            public <E extends ExecutionEvent> void publish(E event) {
                sink.add(event);
            }

            @Override
            public <E extends ExecutionEvent> me.virul.nexora.event.Subscription subscribe(
                    Class<E> t, me.virul.nexora.event.EventHandler<E> h) {
                return () -> {};
            }
        };
    }

    @Test
    void remainsHealthyUntilMinimumSamplesAreCollected() {
        CapabilityContractMonitor monitor = new CapabilityContractMonitor();
        CapabilityContract contract =
                CapabilityContract.builder().maxErrorRate(0.0).build();

        for (int i = 0; i < 4; i++) {
            monitor.recordFailure("cap", Duration.ofMillis(10));
        }

        assertTrue(monitor.isHealthy("cap", contract));
    }

    @Test
    void becomesUnhealthyWhenErrorRateBreachIsObserved() {
        List<ExecutionEvent> events = new ArrayList<>();
        CapabilityContractMonitor monitor = new CapabilityContractMonitor(capturingBus(events));
        CapabilityContract contract =
                CapabilityContract.builder().maxErrorRate(0.20).build();

        for (int i = 0; i < 5; i++) {
            monitor.recordFailure("cap", Duration.ofMillis(10));
        }

        assertFalse(monitor.isHealthy("cap", contract));
        assertEquals(
                CapabilityContractMonitor.CircuitState.OPEN,
                monitor.snapshot("cap").state());
        assertEquals(1, events.size());
        assertTrue(events.get(0) instanceof CapabilityCircuitOpenedEvent);
    }

    @Test
    void becomesUnhealthyWhenLatencySlaIsBreached() {
        CapabilityContractMonitor monitor = new CapabilityContractMonitor();
        CapabilityContract contract =
                CapabilityContract.builder().p99Latency(Duration.ofMillis(100)).build();

        for (int i = 0; i < 5; i++) {
            monitor.recordSuccess("cap", Duration.ofMillis(250));
        }

        assertFalse(monitor.isHealthy("cap", contract));
        assertEquals(
                CapabilityContractMonitor.CircuitState.OPEN,
                monitor.snapshot("cap").state());
    }

    @Test
    void circuitBreakerTransitionsToHalfOpenAndThenClosed() throws InterruptedException {
        List<ExecutionEvent> events = new ArrayList<>();
        CapabilityContractMonitor monitor = new CapabilityContractMonitor(capturingBus(events));
        CapabilityContract contract = CapabilityContract.builder()
                .maxErrorRate(0.0)
                .openDuration(Duration.ofMillis(50))
                .probeInterval(Duration.ofMillis(50))
                .build();

        // 1. Force OPEN
        for (int i = 0; i < 5; i++) {
            monitor.recordFailure("cap", Duration.ofMillis(10));
        }
        assertFalse(monitor.isHealthy("cap", contract)); // State becomes OPEN
        assertEquals(
                CapabilityContractMonitor.CircuitState.OPEN,
                monitor.snapshot("cap").state());

        // 2. Wait for openDuration to pass
        Thread.sleep(150);

        // 3. Should transition to HALF_OPEN and allow probe
        assertTrue(monitor.isHealthy("cap", contract));
        assertEquals(
                CapabilityContractMonitor.CircuitState.HALF_OPEN,
                monitor.snapshot("cap").state());

        // 4. Following checks in HALF_OPEN before probeInterval elapses should return false
        assertFalse(monitor.isHealthy("cap", contract));

        // 5. Successful probe should CLOSE the circuit
        monitor.recordSuccess("cap", Duration.ofMillis(10));
        assertEquals(
                CapabilityContractMonitor.CircuitState.CLOSED,
                monitor.snapshot("cap").state());

        // 6. Sliding window should be cleared, so it's healthy
        assertTrue(monitor.isHealthy("cap", contract));

        boolean hasClosedEvent = events.stream().anyMatch(e -> e instanceof CapabilityCircuitClosedEvent);
        assertTrue(hasClosedEvent);
    }

    @Test
    void probeFailureInHalfOpenReopensCircuit() throws InterruptedException {
        List<ExecutionEvent> events = new ArrayList<>();
        CapabilityContractMonitor monitor = new CapabilityContractMonitor(capturingBus(events));
        CapabilityContract contract = CapabilityContract.builder()
                .maxErrorRate(0.0)
                .openDuration(Duration.ofMillis(50))
                .probeInterval(Duration.ofMillis(50))
                .build();

        // 1. Force OPEN
        for (int i = 0; i < 5; i++) {
            monitor.recordFailure("cap", Duration.ofMillis(10));
        }
        assertFalse(monitor.isHealthy("cap", contract)); // State becomes OPEN

        // 2. Wait for openDuration to pass
        Thread.sleep(150);

        // 3. Should transition to HALF_OPEN and allow probe
        assertTrue(monitor.isHealthy("cap", contract));
        assertEquals(
                CapabilityContractMonitor.CircuitState.HALF_OPEN,
                monitor.snapshot("cap").state());

        // 4. Failed probe should re-OPEN the circuit
        monitor.recordFailure("cap", Duration.ofMillis(10));
        assertEquals(
                CapabilityContractMonitor.CircuitState.OPEN,
                monitor.snapshot("cap").state());

        long openedEvents = events.stream()
                .filter(e -> e instanceof CapabilityCircuitOpenedEvent)
                .count();
        assertEquals(2, openedEvents); // initial open + reopen
    }

    @Test
    void snapshotReflectsRecordedWindowStats() {
        CapabilityContractMonitor monitor = new CapabilityContractMonitor();
        monitor.recordSuccess("cap", Duration.ofMillis(10));
        monitor.recordFailure("cap", Duration.ofMillis(20));
        monitor.recordSuccess("cap", Duration.ofMillis(30));

        CapabilityContractMonitor.HealthSnapshot snapshot = monitor.snapshot("cap");

        assertEquals("cap", snapshot.capabilityId());
        assertEquals(CapabilityContractMonitor.CircuitState.CLOSED, snapshot.state());
        assertEquals(3, snapshot.sampleCount());
        assertEquals(1.0 / 3.0, snapshot.errorRate(), 0.0001);
        assertEquals(Duration.ofMillis(30), snapshot.p99Latency());
    }

    @Test
    void concurrentBreachOpensCircuitOnceAndPublishesOneEvent() throws Exception {
        List<ExecutionEvent> events = new CopyOnWriteArrayList<>();
        CapabilityContractMonitor monitor = new CapabilityContractMonitor(capturingBus(events));
        CapabilityContract contract = CapabilityContract.builder()
                .maxErrorRate(0.5)
                .fallback("fallback")
                .build();
        for (int i = 0; i < 20; i++) {
            monitor.recordFailure("cap", Duration.ofMillis(1));
        }

        List<Boolean> healthy = concurrently(16, () -> monitor.isHealthy("cap", contract));

        assertTrue(healthy.stream().noneMatch(Boolean::booleanValue));
        assertEquals(
                1,
                events.stream()
                        .filter(CapabilityCircuitOpenedEvent.class::isInstance)
                        .count());
        assertEquals(
                CapabilityContractMonitor.CircuitState.OPEN,
                monitor.snapshot("cap").state());
    }

    @Test
    void halfOpenCircuitLetsExactlyOneProbeThroughAtOnce() throws Exception {
        CapabilityContractMonitor monitor = new CapabilityContractMonitor();
        CapabilityContract contract = CapabilityContract.builder()
                .maxErrorRate(0.5)
                .fallback("fallback")
                .openDuration(Duration.ofMillis(20))
                .probeInterval(Duration.ofSeconds(30))
                .build();
        for (int i = 0; i < 20; i++) {
            monitor.recordFailure("cap", Duration.ofMillis(1));
        }
        assertFalse(monitor.isHealthy("cap", contract));
        Thread.sleep(60);

        List<Boolean> healthy = concurrently(16, () -> monitor.isHealthy("cap", contract));

        assertEquals(1, healthy.stream().filter(Boolean::booleanValue).count());
        assertEquals(
                CapabilityContractMonitor.CircuitState.HALF_OPEN,
                monitor.snapshot("cap").state());
    }

    @Test
    void concurrentRecordingKeepsTheFailureCountExact() throws Exception {
        CapabilityContractMonitor monitor = new CapabilityContractMonitor();

        concurrently(8, () -> {
            for (int i = 0; i < 5_000; i++) {
                if (i % 3 == 0) {
                    monitor.recordFailure("cap", Duration.ofMillis(1));
                } else {
                    monitor.recordSuccess("cap", Duration.ofMillis(1));
                }
            }
            return true;
        });
        CapabilityContractMonitor.HealthSnapshot afterRace = monitor.snapshot("cap");
        for (int i = 0; i < CapabilityContract.DEFAULT_WINDOW; i++) {
            monitor.recordSuccess("cap", Duration.ofMillis(1));
        }

        assertEquals(CapabilityContract.DEFAULT_WINDOW, afterRace.sampleCount());
        assertTrue(afterRace.errorRate() >= 0.0 && afterRace.errorRate() <= 1.0);
        assertEquals(0.0, monitor.snapshot("cap").errorRate());
    }

    @Test
    void windowKeepsOnlyTheMostRecentCalls() {
        CapabilityContractMonitor monitor = new CapabilityContractMonitor();
        for (int i = 0; i < CapabilityContract.DEFAULT_WINDOW; i++) {
            monitor.recordFailure("cap", Duration.ofMillis(500));
        }
        for (int i = 0; i < CapabilityContract.DEFAULT_WINDOW; i++) {
            monitor.recordSuccess("cap", Duration.ofMillis(5));
        }

        CapabilityContractMonitor.HealthSnapshot snapshot = monitor.snapshot("cap");

        assertEquals(CapabilityContract.DEFAULT_WINDOW, snapshot.sampleCount());
        assertEquals(0.0, snapshot.errorRate());
        assertEquals(Duration.ofMillis(5), snapshot.p99Latency());
    }

    @Test
    void p99OfALargeWindowIsTheNinetyNinthPercentile() {
        DefaultCapabilityRegistry registry = new DefaultCapabilityRegistry();
        CapabilityContract contract =
                CapabilityContract.builder().windowSize(200).build();
        registry.register(
                new CapabilityDescriptor("cap", "cap", List.of(), List.of(), true, false, contract), request -> null);
        CapabilityContractMonitor monitor = new CapabilityContractMonitor(capturingBus(new ArrayList<>()), registry);
        List<Integer> latencies =
                new ArrayList<>(IntStream.rangeClosed(1, 200).boxed().toList());
        java.util.Collections.shuffle(latencies, new java.util.Random(7));
        latencies.forEach(ms -> monitor.recordSuccess("cap", Duration.ofMillis(ms)));

        assertEquals(Duration.ofMillis(198), monitor.snapshot("cap").p99Latency());
        assertEquals(200, monitor.snapshot("cap").sampleCount());
    }

    private static <T> List<T> concurrently(int threads, java.util.concurrent.Callable<T> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return task.call();
                }));
            }
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}
