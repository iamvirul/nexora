package me.virul.nexora.planner.engine;

import java.util.List;
import me.virul.nexora.core.intent.Intent;
import me.virul.nexora.core.plan.Plan;
import me.virul.nexora.core.plan.Step;
import me.virul.nexora.planner.model.StepDefinition;
import me.virul.nexora.planner.registry.PlanRegistry;

public class PlannerEngine {

    private final PlanRegistry registry;

    public PlannerEngine(PlanRegistry registry) {
        this.registry = registry;
    }

    public Plan createPlan(Intent intent) {
        String goal = intent.getGoal();

        List<Step> steps = registry.getMatching(goal).stream().map(this::toStep).toList();

        return new Plan(steps);
    }

    private Step toStep(StepDefinition def) {
        return new Step(
                def.getId(),
                def.getCapabilityId(),
                def.getInputs(),
                def.getOutputKey(),
                def.getDependsOn(),
                def.getRetryPolicyId(),
                def.getTimeout(),
                def.getCompensateCapabilityId(),
                def.getCondition());
    }
}
