package me.virul.nexora.event;

import java.time.Instant;

/**
 * A plugin was swapped for a new JAR without stopping the engine. New executions use
 * {@code newVersion} from now on; invocations already running on {@code previousVersion} finish on it.
 */
public record PluginReloadedEvent(String pluginId, String previousVersion, String newVersion, Instant occurredAt)
        implements ExecutionEvent {}
