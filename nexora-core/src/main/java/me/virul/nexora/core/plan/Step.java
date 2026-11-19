package me.virul.nexora.core.plan;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * One node of a plan: a capability call, how its inputs are bound, and where it sits in the DAG.
 *
 * @param id                     the step id, unique within the plan
 * @param capabilityId           the capability to invoke
 * @param inputs                 how each input is resolved, by input name; never null and immutable
 * @param outputKey              the execution context key the step output is written to, or
 *                               {@code null} to write it nowhere but the step output
 * @param dependsOn              the ids of the steps that must complete before this one runs; never
 *                               null and immutable
 * @param retryPolicyId          the registered retry policy to use, or {@code null} for the engine
 *                               default
 * @param timeout                the time limit of each attempt, or {@code null} for the capability's
 *                               p99 latency contract, else the engine default
 * @param compensateCapabilityId the capability invoked to undo this step on saga rollback, or
 *                               {@code null} for no compensation
 * @param condition              evaluated before the step starts; when false the step is skipped.
 *                               {@code null} means it always runs
 */
public record Step(
        String id,
        String capabilityId,
        Map<String, InputBinding> inputs,
        String outputKey,
        Set<String> dependsOn,
        String retryPolicyId,
        Duration timeout,
        String compensateCapabilityId,
        StepCondition condition) {

    /**
     * Validates the step and copies its inputs and dependencies, treating {@code null} as empty.
     *
     * @throws NullPointerException if {@code id} or {@code capabilityId} is null
     */
    public Step {
        Objects.requireNonNull(id, "Step id must not be null");
        Objects.requireNonNull(capabilityId, "capabilityId must not be null");
        inputs = inputs == null ? Map.of() : Map.copyOf(inputs);
        dependsOn = dependsOn == null ? Set.of() : Set.copyOf(dependsOn);
    }

    /**
     * Convenience factory for steps with no inputs, no dependencies, and no compensation.
     *
     * @param id           the step id
     * @param capabilityId the capability to invoke
     * @return the step, with every optional component unset
     */
    public static Step of(String id, String capabilityId) {
        return new Step(id, capabilityId, Map.of(), null, Set.of(), null, null, null, null);
    }
}
