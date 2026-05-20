package com.nexora.runtime.engine;

import com.nexora.core.execution.ExecutionResult;
import com.nexora.core.execution.ExecutionStatus;
import com.nexora.core.execution.StepResult;
import com.nexora.core.plan.Plan;
import com.nexora.core.plan.Step;
import com.nexora.core.context.ExecutionContext;
import com.nexora.runtime.executor.StepExecutor;

import java.util.ArrayList;
import java.util.List;

public record ExecutionEngine(StepExecutor stepExecutor) {

    public ExecutionResult execute(Plan plan, ExecutionContext context) {
        List<StepResult> results = new ArrayList<>();

        for (Step step : plan.getSteps()) {
            StepResult result = stepExecutor.execute(step, context);
            results.add(result);

            if (result.getStatus() == ExecutionStatus.FAILED) {
                return new ExecutionResult(
                        ExecutionStatus.FAILED,
                        results
                );
            }
        }
        return new ExecutionResult(
                ExecutionStatus.COMPLETED,
                results
        );
    }
}
