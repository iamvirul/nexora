package com.nexora.event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;

/**
 * In-process event bus backed by CopyOnWriteArrayList per event type.
 * CopyOnWrite gives safe concurrent iteration without holding a lock during dispatch,
 * at the cost of more expensive writes (subscribe/unsubscribe) — the right trade-off
 * since reads (publish) happen far more frequently.
 *
 * Handler failures are caught and logged; they must never propagate to the publisher.
 */
public final class InProcessEventBus implements ExecutionEventBus {

    private static final Logger log = LoggerFactory.getLogger(InProcessEventBus.class);

    private final ConcurrentHashMap<Class<?>, CopyOnWriteArrayList<SubscriptionEntry<?>>> handlers
            = new ConcurrentHashMap<>();
    private final Executor dispatchExecutor;

    public InProcessEventBus(Executor dispatchExecutor) {
        this.dispatchExecutor = Objects.requireNonNull(dispatchExecutor, "dispatchExecutor must not be null");
    }

    @Override
    @SuppressWarnings("unchecked")
    public <E extends ExecutionEvent> void publish(E event) {
        Objects.requireNonNull(event, "event must not be null");
        CopyOnWriteArrayList<SubscriptionEntry<?>> entries = handlers.get(event.getClass());
        if (entries == null || entries.isEmpty()) return;

        for (SubscriptionEntry<?> entry : entries) {
            if (!entry.active) continue;
            if (entry.synchronous) {
                dispatch((EventHandler<E>) entry.handler, event);
            } else {
                dispatchExecutor.execute(() -> dispatch((EventHandler<E>) entry.handler, event));
            }
        }
    }

    private static <E extends ExecutionEvent> void dispatch(EventHandler<E> handler, E event) {
        try {
            handler.onEvent(event);
        } catch (Exception e) {
            log.warn("Event handler threw for event={} handler={}",
                    event.getClass().getSimpleName(), handler.getClass().getSimpleName(), e);
        }
    }

    @Override
    public <E extends ExecutionEvent> Subscription subscribe(Class<E> eventType, EventHandler<E> handler) {
        Objects.requireNonNull(eventType, "eventType must not be null");
        Objects.requireNonNull(handler, "handler must not be null");

        return register(eventType, handler, false);
    }

    @Override
    public <E extends ExecutionEvent> Subscription subscribeSync(Class<E> eventType, EventHandler<E> handler) {
        Objects.requireNonNull(eventType, "eventType must not be null");
        Objects.requireNonNull(handler, "handler must not be null");
        return register(eventType, handler, true);
    }

    private <E extends ExecutionEvent> Subscription register(Class<E> eventType, EventHandler<E> handler, boolean synchronous) {
        SubscriptionEntry<E> entry = new SubscriptionEntry<>(handler, synchronous);
        handlers.computeIfAbsent(eventType, k -> new CopyOnWriteArrayList<>()).add(entry);
        return () -> entry.active = false;
    }

    private static final class SubscriptionEntry<E extends ExecutionEvent> {
        final EventHandler<E> handler;
        final boolean synchronous;
        volatile boolean active = true;

        SubscriptionEntry(EventHandler<E> handler, boolean synchronous) {
            this.handler = handler;
            this.synchronous = synchronous;
        }
    }
}
