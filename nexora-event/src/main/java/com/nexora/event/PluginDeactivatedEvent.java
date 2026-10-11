package com.nexora.event;

import java.time.Instant;

public record PluginDeactivatedEvent(String pluginId, Instant occurredAt) implements ExecutionEvent {}
