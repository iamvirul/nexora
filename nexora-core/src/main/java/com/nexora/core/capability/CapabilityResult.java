package com.nexora.core.capability;

import com.nexora.core.plan.PlanAmendment;

import java.util.List;
import java.util.Map;

public record CapabilityResult(
        ResultStatus status,
        Object output,
        String failureCode,
        String failureMessage,
        Map<String, String> metadata,
        List<PlanAmendment> amendments
) {
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

    public boolean succeeded() {
        return status == ResultStatus.SUCCESS;
    }
}
