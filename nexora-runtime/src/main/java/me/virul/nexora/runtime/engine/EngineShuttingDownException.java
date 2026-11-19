package me.virul.nexora.runtime.engine;

/**
 * Thrown when work is submitted to an engine that has begun shutting down. The engine is draining
 * its in-flight executions and accepts no new ones; the caller should send the work to another
 * instance or retry it after a restart.
 */
public final class EngineShuttingDownException extends IllegalStateException {

    public EngineShuttingDownException() {
        super("Engine is shutting down and accepts no new executions");
    }
}
