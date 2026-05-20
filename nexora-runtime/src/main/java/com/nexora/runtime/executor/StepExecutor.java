package com.nexora.runtime.executor;

import com.nexora.core.execution.StepResult;
import com.nexora.core.plan.Step;
import com.nexora.core.context.ExecutionContext;

public interface StepExecutor {
    StepResult execute(Step step, ExecutionContext context);
}
