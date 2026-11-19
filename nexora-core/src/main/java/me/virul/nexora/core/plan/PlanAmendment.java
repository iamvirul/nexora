package me.virul.nexora.core.plan;

/**
 * A mutation that a completed step may request against the remaining plan.
 *
 * <p>Amendments are extracted from a step's
 * {@link me.virul.nexora.core.capability.CapabilityResult#amendments()} and applied by the scheduler
 * before any dependent steps begin. This lets capabilities reshape execution at runtime based on the
 * data they produce, without hard-coding conditional logic in the planner.
 *
 * <p>Three kinds of amendment exist:
 * <ul>
 *   <li>{@link AddStepAmendment}: inject a new step into the running DAG</li>
 *   <li>{@link SkipStepAmendment}: cancel a pending step before it starts</li>
 *   <li>{@link ModifyInputAmendment}: override an input binding for a pending step</li>
 * </ul>
 */
public sealed interface PlanAmendment permits AddStepAmendment, SkipStepAmendment, ModifyInputAmendment {}
