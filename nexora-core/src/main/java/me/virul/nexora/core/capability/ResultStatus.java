package me.virul.nexora.core.capability;

/** The outcome of a capability call, as carried by {@link CapabilityResult#status()}. */
public enum ResultStatus {
    /** The call completed and produced its output. */
    SUCCESS,
    /** The call failed; see {@link CapabilityResult#failureCode()}. */
    FAILURE,
    /** The step was not run, because of a condition, a skip amendment or a failed dependency. */
    SKIPPED
}
