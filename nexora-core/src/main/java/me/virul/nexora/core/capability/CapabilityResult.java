package me.virul.nexora.core.capability;

import java.util.List;
import java.util.Map;
import me.virul.nexora.core.plan.PlanAmendment;

/**
 * The outcome of one capability call. Create one with the static factories.
 *
 * @param status         whether the call succeeded, failed or was skipped
 * @param output         the value a successful call produced, or {@code null}; it becomes the step's
 *                       output for later steps
 * @param failureCode    a machine-readable reason when the call did not succeed, such as
 *                       {@link #SKIPPED}; {@code null} on success
 * @param failureMessage a human-readable reason when the call did not succeed; {@code null} on success
 * @param metadata       extra details about the call; never null and immutable
 * @param amendments     changes to the remaining plan that a successful step requests; never null and
 *                       immutable
 */
public record CapabilityResult(
        ResultStatus status,
        Object output,
        String failureCode,
        String failureMessage,
        Map<String, String> metadata,
        List<PlanAmendment> amendments) {
    /** {@link #failureCode()} of a result skipped by a step condition or a skip amendment. */
    public static final String SKIPPED = "SKIPPED";

    /**
     * {@link #failureCode()} of a step skipped because a step it depends on failed. Unlike other
     * skips, it is passed on: the steps that depend on this one are skipped too.
     */
    public static final String DEPENDENCY_FAILED = "DEPENDENCY_FAILED";

    /** Copies {@code metadata} and {@code amendments}, treating {@code null} as empty. */
    public CapabilityResult {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        amendments = amendments == null ? List.of() : List.copyOf(amendments);
    }

    /**
     * A successful result.
     *
     * @param output the value produced, or {@code null}
     * @return the result
     */
    public static CapabilityResult success(Object output) {
        return new CapabilityResult(ResultStatus.SUCCESS, output, null, null, Map.of(), List.of());
    }

    /**
     * A successful result that also changes the remaining plan.
     *
     * @param output     the value produced, or {@code null}
     * @param amendments the changes to apply before dependent steps start
     * @return the result
     */
    public static CapabilityResult success(Object output, List<PlanAmendment> amendments) {
        return new CapabilityResult(ResultStatus.SUCCESS, output, null, null, Map.of(), amendments);
    }

    /**
     * A failed result. The engine's default retry policy decides whether the call is tried again.
     *
     * @param code    a machine-readable reason
     * @param message a human-readable reason
     * @return the result
     */
    public static CapabilityResult failure(String code, String message) {
        return new CapabilityResult(ResultStatus.FAILURE, null, code, message, Map.of(), List.of());
    }

    /**
     * A result for a step that was not run, with failure code {@link #SKIPPED}.
     *
     * @param message why the step was skipped
     * @return the result
     */
    public static CapabilityResult skipped(String message) {
        return new CapabilityResult(ResultStatus.SKIPPED, null, SKIPPED, message, Map.of(), List.of());
    }

    /**
     * Skipped, not run, because the dependency {@code failedStepId} failed or was itself skipped for that.
     *
     * @param failedStepId the dependency that failed
     * @return a skipped result with failure code {@link #DEPENDENCY_FAILED}
     */
    public static CapabilityResult dependencyFailed(String failedStepId) {
        return new CapabilityResult(
                ResultStatus.SKIPPED,
                null,
                DEPENDENCY_FAILED,
                "Dependency '" + failedStepId + "' failed",
                Map.of(),
                List.of());
    }

    /**
     * Whether the steps that depend on this one must be skipped: true for a failure, and for a step
     * that was itself skipped because a dependency failed.
     *
     * @return {@code true} when this result skips the dependent steps
     */
    public boolean isDependencyFailure() {
        return status == ResultStatus.FAILURE
                || (status == ResultStatus.SKIPPED && DEPENDENCY_FAILED.equals(failureCode));
    }

    /**
     * Whether the call succeeded.
     *
     * @return {@code true} when {@link #status()} is {@link ResultStatus#SUCCESS}
     */
    public boolean succeeded() {
        return status == ResultStatus.SUCCESS;
    }
}
