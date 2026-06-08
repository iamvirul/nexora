package com.nexora.event;

import java.time.Instant;

public record ExecutionDeadLetteredEvent(
        String executionId,
        String deadLetterId,
        String failureCode,
        String failureMessage,
        Instant occurredAt
) implements ExecutionEvent {}
