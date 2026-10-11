package com.nexora.persistence;

public enum StepState {
    PENDING,
    RUNNING,
    COMPLETED,
    FAILED,
    SKIPPED,
    COMPENSATING,
    COMPENSATED,
    COMPENSATION_FAILED
}
