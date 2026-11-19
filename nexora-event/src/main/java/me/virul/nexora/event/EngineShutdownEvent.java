package me.virul.nexora.event;

import java.time.Duration;
import java.time.Instant;

/**
 * The engine finished draining on shutdown and is about to release its plugins and store.
 *
 * <p>Published once per engine. During JVM shutdown an asynchronous subscriber may not get to run
 * before the process exits; subscribe with {@code subscribeSync} to be sure of seeing it.
 *
 * @param inFlight   executions running when shutdown began
 * @param completed  executions that finished on their own within the drain timeout
 * @param cancelled  executions cancelled after the drain timeout, compensated when saga is enabled
 * @param unfinished executions still not finished when the engine stopped waiting
 * @param elapsed    how long the drain took
 */
public record EngineShutdownEvent(
        int inFlight, int completed, int cancelled, int unfinished, Duration elapsed, Instant occurredAt)
        implements ExecutionEvent {}
