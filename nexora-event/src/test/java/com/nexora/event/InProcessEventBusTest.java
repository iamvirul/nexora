package com.nexora.event;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InProcessEventBusTest {

    // Executor that never runs anything: proves sync handlers do not depend on it.
    private static final Executor NEVER = task -> {};

    @Test
    void syncHandlerRunsOnPublishingThreadBeforePublishReturns() {
        InProcessEventBus bus = new InProcessEventBus(NEVER);
        AtomicReference<Thread> handledOn = new AtomicReference<>();
        bus.subscribeSync(PlanStartedEvent.class, e -> handledOn.set(Thread.currentThread()));

        bus.publish(started("e1"));

        assertEquals(Thread.currentThread(), handledOn.get());
    }

    @Test
    void syncHandlersSeeEventsInPublishOrder() {
        InProcessEventBus bus = new InProcessEventBus(NEVER);
        List<String> seen = new CopyOnWriteArrayList<>();
        bus.subscribeSync(PlanStartedEvent.class, e -> seen.add(e.executionId()));

        for (int i = 0; i < 50; i++) {
            bus.publish(started("e" + i));
        }

        assertEquals(50, seen.size());
        for (int i = 0; i < 50; i++) {
            assertEquals("e" + i, seen.get(i));
        }
    }

    @Test
    void asyncHandlersStillRunOnTheDispatchExecutor() throws Exception {
        InProcessEventBus bus = new InProcessEventBus(task -> Thread.ofVirtual().start(task));
        CountDownLatch handled = new CountDownLatch(1);
        AtomicReference<Thread> handledOn = new AtomicReference<>();
        bus.subscribe(PlanStartedEvent.class, e -> {
            handledOn.set(Thread.currentThread());
            handled.countDown();
        });

        bus.publish(started("e1"));

        assertTrue(handled.await(5, TimeUnit.SECONDS));
        assertTrue(handledOn.get() != Thread.currentThread());
    }

    @Test
    void throwingSyncHandlerDoesNotBreakPublishOrOtherHandlers() {
        InProcessEventBus bus = new InProcessEventBus(NEVER);
        List<String> seen = new CopyOnWriteArrayList<>();
        bus.subscribeSync(PlanStartedEvent.class, e -> { throw new IllegalStateException("boom"); });
        bus.subscribeSync(PlanStartedEvent.class, e -> seen.add(e.executionId()));

        bus.publish(started("e1"));

        assertEquals(List.of("e1"), seen);
    }

    @Test
    void cancelledSyncSubscriptionStopsReceiving() {
        InProcessEventBus bus = new InProcessEventBus(NEVER);
        List<String> seen = new CopyOnWriteArrayList<>();
        Subscription subscription = bus.subscribeSync(PlanStartedEvent.class, e -> seen.add(e.executionId()));

        subscription.cancel();
        bus.publish(started("e1"));

        assertTrue(seen.isEmpty());
    }

    private static PlanStartedEvent started(String executionId) {
        return new PlanStartedEvent(executionId, "trace", Instant.now());
    }
}
