package com.nexora.core.plan;

/**
 * A mutation that a completed step may request against the remaining plan.
 *
 * Amendments are extracted from a step's CapabilityResult and applied by the
 * scheduler before any dependent steps begin. This lets capabilities reshape
 * execution at runtime based on the data they produce — without hard-coding
 * conditional logic in the planner.
 *
 * Three kinds of amendment exist:
 *   AddStepAmendment    — inject a new step into the running DAG
 *   SkipStepAmendment   — cancel a pending step before it starts
 *   ModifyInputAmendment — override an input binding for a pending step
 */
public sealed interface PlanAmendment
        permits AddStepAmendment, SkipStepAmendment, ModifyInputAmendment {
}
