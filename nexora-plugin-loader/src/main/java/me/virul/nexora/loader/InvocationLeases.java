package me.virul.nexora.loader;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Counts the invocations running on one loaded version of a plugin, so the version can be released
 * (plugin shut down, class loader closed) only once nothing is executing its code any more.
 *
 * <p>After {@link #retire()}, the release action runs exactly once: immediately when nothing is in
 * flight, otherwise when the last in-flight invocation finishes. A {@link #tryAcquire()} racing with
 * that release either wins (and holds the version open until it releases) or is refused; it never
 * runs on a version that has already been released.
 */
final class InvocationLeases {

    /** Count value once the release action has been claimed; no lease can be taken after it. */
    private static final int RELEASED = -1;

    private final AtomicInteger active = new AtomicInteger();
    private final AtomicBoolean retired = new AtomicBoolean();
    private final Runnable releaseAction;

    InvocationLeases(Runnable releaseAction) {
        this.releaseAction = Objects.requireNonNull(releaseAction, "releaseAction must not be null");
    }

    /** @return true when the caller may invoke the version and must call {@link #release()} after */
    boolean tryAcquire() {
        while (true) {
            int current = active.get();
            if (current == RELEASED) {
                return false;
            }
            if (active.compareAndSet(current, current + 1)) {
                return true;
            }
        }
    }

    void release() {
        int remaining = active.decrementAndGet();
        if (remaining < 0) {
            throw new IllegalStateException("release() without a matching tryAcquire()");
        }
        if (remaining == 0 && retired.get()) {
            releaseIfIdle();
        }
    }

    /** Marks the version as replaced or deactivated. Safe to call more than once. */
    void retire() {
        if (retired.compareAndSet(false, true)) {
            releaseIfIdle();
        }
    }

    int inFlight() {
        return Math.max(active.get(), 0);
    }

    boolean released() {
        return active.get() == RELEASED;
    }

    private void releaseIfIdle() {
        // Only the 0 -> RELEASED transition runs the action, so a lease taken concurrently keeps it open.
        if (active.compareAndSet(0, RELEASED)) {
            releaseAction.run();
        }
    }
}
