package com.nexora.runtime.executor;

import com.nexora.core.execution.ExecutionStatus;
import com.nexora.core.execution.StepResult;
import com.nexora.core.plan.Step;
import com.nexora.core.context.ExecutionContext;

public class DefaultStepExecutor implements StepExecutor{

    @Override
    public StepResult execute(Step step, ExecutionContext context) {
        try {
            // simple simulation logic
            System.out.println("Executing step: " + step.getName());

            // store debug info
            context.put(step.getName(), "done");

            return new StepResult(
                    step.getName(),
                    ExecutionStatus.COMPLETED,
                    "Executed successfully"
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
