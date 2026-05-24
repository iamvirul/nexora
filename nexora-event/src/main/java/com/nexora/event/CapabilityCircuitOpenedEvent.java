package com.nexora.event;

import java.time.Instant;

public record CapabilityCircuitOpenedEvent(
        String capabilityId,
        Instant occurredAt
) implements ExecutionEvent {}
