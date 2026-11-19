package me.virul.nexora.runtime.engine;

import java.time.Duration;
import java.util.Objects;

/**
 * What happened to the executions that were in flight when the engine stopped accepting work.
 *
 * @param inFlight   executions running when the drain began
 * @param completed  executions that finished on their own within the drain timeout
 * @param cancelled  executions the drain cancelled after the timeout, with saga compensation when enabled
 * @param unfinished executions still not finished when the drain gave up waiting; their stored
 *                   state may be left at {@code RUNNING} or {@code COMPENSATING}
 * @param elapsed    how long the drain took
 */
public record DrainResult(int inFlight, int completed, int cancelled, int unfinished, Duration elapsed) {

    public DrainResult {
        if (inFlight < 0 || completed < 0 || cancelled < 0 || unfinished < 0) {
            throw new IllegalArgumentException("drain counts must not be negative");
        }
        Objects.requireNonNull(elapsed, "elapsed must not be null");
    }

    /** True when every in-flight execution reached a terminal state before the engine stopped. */
    public boolean clean() {
        return unfinished == 0;
    }
}
