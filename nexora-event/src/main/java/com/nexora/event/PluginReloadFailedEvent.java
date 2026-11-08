package com.nexora.event;

import java.time.Instant;

/**
 * A plugin reload was refused or failed. The version that was active before, {@code activeVersion},
 * is still active and serving every capability and planner it served before the attempt.
 *
 * @param reason why the new JAR could not replace it, safe to show an operator
 */
public record PluginReloadFailedEvent(String pluginId, String activeVersion, String reason, Instant occurredAt)
        implements ExecutionEvent {}
