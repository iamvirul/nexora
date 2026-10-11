package com.nexora.event;

public interface ExecutionEventBus {

    <E extends ExecutionEvent> void publish(E event);

    <E extends ExecutionEvent> Subscription subscribe(Class<E> eventType, EventHandler<E> handler);

    /**
     * Subscribes a handler that runs on the publishing thread, before {@link #publish} returns and in
     * publish order. Use it for handlers whose effects must be visible once the publisher moves on,
     * such as persisting step state; keep them short, since they delay the publisher.
     *
     * <p>The default falls back to {@link #subscribe}, for buses that cannot run handlers inline.
     */
    default <E extends ExecutionEvent> Subscription subscribeSync(Class<E> eventType, EventHandler<E> handler) {
        return subscribe(eventType, handler);
    }
}
