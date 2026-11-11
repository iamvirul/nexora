package me.virul.nexora.core.capability;

import java.util.List;
import java.util.Map;
import me.virul.nexora.core.plan.PlanAmendment;

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

    public CapabilityResult {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        amendments = amendments == null ? List.of() : List.copyOf(amendments);
    }

    public static CapabilityResult success(Object output) {
        return new CapabilityResult(ResultStatus.SUCCESS, output, null, null, Map.of(), List.of());
    }

    public static CapabilityResult success(Object output, List<PlanAmendment> amendments) {
        return new CapabilityResult(ResultStatus.SUCCESS, output, null, null, Map.of(), amendments);
    }

    public static CapabilityResult failure(String code, String message) {
        return new CapabilityResult(ResultStatus.FAILURE, null, code, message, Map.of(), List.of());
    }

    public static CapabilityResult skipped(String message) {
        return new CapabilityResult(ResultStatus.SKIPPED, null, SKIPPED, message, Map.of(), List.of());
    }

    /** Skipped, not run, because the dependency {@code failedStepId} failed or was itself skipped for that. */
    public static CapabilityResult dependencyFailed(String failedStepId) {
        return new CapabilityResult(
                ResultStatus.SKIPPED,
                null,
                DEPENDENCY_FAILED,
                "Dependency '" + failedStepId + "' failed",
                Map.of(),
                List.of());
    }

    /** True when this step was skipped because a dependency failed; such a skip passes on to dependents. */
    public boolean isDependencyFailure() {
        return status == ResultStatus.FAILURE
                || (status == ResultStatus.SKIPPED && DEPENDENCY_FAILED.equals(failureCode));
    }

    public boolean succeeded() {
        return status == ResultStatus.SUCCESS;
    }
}
