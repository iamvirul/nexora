package com.nexora.executor;

import com.nexora.core.capability.CapabilityContract;
import com.nexora.event.CapabilityCircuitClosedEvent;
import com.nexora.event.CapabilityCircuitOpenedEvent;
import com.nexora.event.ExecutionEvent;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CapabilityContractMonitorTest {

    private static com.nexora.event.ExecutionEventBus capturingBus(List<ExecutionEvent> sink) {
        return new com.nexora.event.ExecutionEventBus() {
            @Override public <E extends ExecutionEvent> void publish(E event) { sink.add(event); }
            @Override public <E extends ExecutionEvent> com.nexora.event.Subscription subscribe(
                    Class<E> t, com.nexora.event.EventHandler<E> h) { return () -> {}; }
        };
    }

    @Test
    void remainsHealthyUntilMinimumSamplesAreCollected() {
        CapabilityContractMonitor monitor = new CapabilityContractMonitor();
        CapabilityContract contract = CapabilityContract.builder()
                .maxErrorRate(0.0)
                .build();

        for (int i = 0; i < 4; i++) {
            monitor.recordFailure("cap", Duration.ofMillis(10));
        }

        assertTrue(monitor.isHealthy("cap", contract));
    }

    @Test
    void becomesUnhealthyWhenErrorRateBreachIsObserved() {
        List<ExecutionEvent> events = new ArrayList<>();
        CapabilityContractMonitor monitor = new CapabilityContractMonitor(capturingBus(events));
        CapabilityContract contract = CapabilityContract.builder()
                .maxErrorRate(0.20)
                .build();

        for (int i = 0; i < 5; i++) {
            monitor.recordFailure("cap", Duration.ofMillis(10));
        }

        assertFalse(monitor.isHealthy("cap", contract));
        assertEquals(CapabilityContractMonitor.CircuitState.OPEN, monitor.snapshot("cap").state());
        assertEquals(1, events.size());
        assertTrue(events.get(0) instanceof CapabilityCircuitOpenedEvent);
    }

    @Test
    void becomesUnhealthyWhenLatencySlaIsBreached() {
        CapabilityContractMonitor monitor = new CapabilityContractMonitor();
        CapabilityContract contract = CapabilityContract.builder()
                .p99Latency(Duration.ofMillis(100))
                .build();

        for (int i = 0; i < 5; i++) {
            monitor.recordSuccess("cap", Duration.ofMillis(250));
        }

        assertFalse(monitor.isHealthy("cap", contract));
        assertEquals(CapabilityContractMonitor.CircuitState.OPEN, monitor.snapshot("cap").state());
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
        assertEquals(CapabilityContractMonitor.CircuitState.OPEN, monitor.snapshot("cap").state());
        
        // 2. Wait for openDuration to pass
        Thread.sleep(150);

        // 3. Should transition to HALF_OPEN and allow probe
        assertTrue(monitor.isHealthy("cap", contract));
        assertEquals(CapabilityContractMonitor.CircuitState.HALF_OPEN, monitor.snapshot("cap").state());

        // 4. Following checks in HALF_OPEN before probeInterval elapses should return false
        assertFalse(monitor.isHealthy("cap", contract));

        // 5. Successful probe should CLOSE the circuit
        monitor.recordSuccess("cap", Duration.ofMillis(10));
        assertEquals(CapabilityContractMonitor.CircuitState.CLOSED, monitor.snapshot("cap").state());
        
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
        assertEquals(CapabilityContractMonitor.CircuitState.HALF_OPEN, monitor.snapshot("cap").state());

        // 4. Failed probe should re-OPEN the circuit
        monitor.recordFailure("cap", Duration.ofMillis(10));
        assertEquals(CapabilityContractMonitor.CircuitState.OPEN, monitor.snapshot("cap").state());
        
        long openedEvents = events.stream()
                .filter(e -> e instanceof CapabilityCircuitOpenedEvent).count();
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
}
