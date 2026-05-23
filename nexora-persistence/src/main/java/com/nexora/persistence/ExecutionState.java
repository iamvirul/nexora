package com.nexora.persistence;

public enum ExecutionState {
    RUNNING,
    COMPLETED,
    FAILED,
    TIMED_OUT,
    COMPENSATING,
    COMPENSATED
}
