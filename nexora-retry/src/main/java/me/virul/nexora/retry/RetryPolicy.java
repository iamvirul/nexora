package me.virul.nexora.retry;

import java.time.Duration;

/**
 * Value object describing when and how to retry a failed capability execution.
 * Implementations must be stateless and thread-safe.
 */
public interface RetryPolicy {

    /** Returns true if another attempt should be made. */
    boolean shouldRetry(int attemptsMade, Throwable cause);

    /** Delay to wait before the next attempt. */
    Duration backoffDelay(int attemptsMade);

    static RetryPolicy noRetry() {
        return NoRetryPolicy.INSTANCE;
    }
}
