package com.nexora.event;

import java.time.Instant;

public record CapabilityCircuitClosedEvent(
        String capabilityId,
        Instant occurredAt
) implements ExecutionEvent {}
