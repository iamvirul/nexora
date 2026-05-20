package com.nexora.runtime.executor;

import com.nexora.capability.Capability;
import com.nexora.capability.CapabilityRegistry;
import com.nexora.core.execution.ExecutionStatus;
import com.nexora.core.execution.StepResult;
import com.nexora.core.plan.Step;
import com.nexora.core.context.ExecutionContext;

public class CapabilityStepExecutor implements StepExecutor{
    private final CapabilityRegistry registry;

    public CapabilityStepExecutor(CapabilityRegistry registry) {
        this.registry = registry;
    }
    @Override
    public StepResult execute(Step step, ExecutionContext context) {
        try {
            Capability capability = registry.get(step.getName());

            if (capability == null) {
                return new StepResult(
                        step.getName(),
                        ExecutionStatus.FAILED,
                        "No capability found"
                );
            }

            Object result = capability.execute(context);

            return new StepResult(
                    step.getName(),
                    ExecutionStatus.COMPLETED,
                    result.toString()
            );

        } catch (Exception e) {
            return new StepResult(
                    step.getName(),
                    ExecutionStatus.FAILED,
                    e.getMessage()
            );
        }
    }
}
