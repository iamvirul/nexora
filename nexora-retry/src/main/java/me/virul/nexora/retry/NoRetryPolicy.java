package me.virul.nexora.retry;

import java.time.Duration;

final class NoRetryPolicy implements RetryPolicy {

    static final NoRetryPolicy INSTANCE = new NoRetryPolicy();

    private NoRetryPolicy() {}

    @Override
    public boolean shouldRetry(int attemptsMade, Throwable cause) {
        return false;
    }

    @Override
    public Duration backoffDelay(int attemptsMade) {
        return Duration.ZERO;
    }
}
