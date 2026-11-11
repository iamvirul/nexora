package me.virul.nexora.event;

/** Handle returned when subscribing. Call cancel() to stop receiving events. */
@FunctionalInterface
public interface Subscription {
    void cancel();
}
