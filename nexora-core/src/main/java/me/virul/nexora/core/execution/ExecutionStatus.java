package me.virul.nexora.core.execution;

/** The lifecycle state of an execution. {@link #PENDING} and {@link #RUNNING} are the only non-final states. */
public enum ExecutionStatus {
    /** Accepted but not started yet. */
    PENDING,
    /** Steps are running. */
    RUNNING,
    /** Every step that had to run succeeded. */
    COMPLETED,
    /** A step failed after its retries. */
    FAILED,
    /** The plan deadline passed before the execution finished. */
    TIMED_OUT,
    /** The execution was cancelled, by a caller or by an engine shutdown. */
    CANCELLED
}
