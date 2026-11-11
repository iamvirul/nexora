package me.virul.nexora.event;

import java.time.Instant;

public record PluginActivatedEvent(String pluginId, String version, Instant occurredAt) implements ExecutionEvent {}
