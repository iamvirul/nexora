package com.nexora.cli;

import com.nexora.core.intent.Intent;
import com.nexora.core.plan.Plan;
import com.nexora.core.plan.Step;
import com.nexora.planner.engine.PlannerEngine;
import com.nexora.planner.model.StepDefinition;
import com.nexora.planner.registry.PlanRegistry;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

import com.nexora.core.context.ExecutionContext;
import com.nexora.core.context.TraceContext;

import java.util.Map;
import java.util.concurrent.Callable;

@Command(
        name = "plan",
        mixinStandardHelpOptions = true
)
public class PlanCommand implements Callable<Integer> {

    @ParentCommand
    private NexoraCli parent;

    @Option(names = {"-g", "--goal"}, required = true, description = "Intent goal string.")
    private String goal;

    @Override
    public Integer call() {
        CliConfig config = parent.config();

        PlanRegistry registry = new PlanRegistry();
        for (CliConfig.StepConfig sc : config.steps) {
            String match = sc.matchesGoalContains;
            StepDefinition.Builder sdb = StepDefinition.builder(sc.id, sc.capabilityId)
                    .withMatcher(g -> match == null || g.contains(match));
            if (sc.condition != null) {
                sdb.withCondition(sc.condition.toStepCondition());
            }
            registry.register(sdb.build());
        }

        Plan plan = new PlannerEngine(registry).createPlan(new Intent(goal, Map.of()));

        System.out.printf("Plan for: \"%s\"%n%n", goal);

        if (plan.getSteps().isEmpty()) {
            System.out.println("  (no steps matched — check your config steps[].matchesGoalContains)");
            return 0;
        }

        System.out.printf("  %-24s  %-24s  %-30s  %s%n", "STEP ID", "CAPABILITY", "CONDITION (Dry-run eval)", "DEPENDS ON");
        System.out.println("  " + "─".repeat(90));
        
        ExecutionContext dummyCtx = new ExecutionContext(new Intent(goal, Map.of()), TraceContext.root());
        
        for (Step step : plan.getSteps()) {
            String deps = step.dependsOn().isEmpty() ? "—" : String.join(", ", step.dependsOn());
            String condStr = "—";
            if (step.condition() != null) {
                boolean result = step.condition().evaluate(dummyCtx);
                condStr = String.format("%s (eval: %b)", step.condition(), result);
            }
            System.out.printf("  %-24s  %-24s  %-30s  %s%n", step.id(), step.capabilityId(), condStr, deps);
        }
        System.out.printf("%n%d step(s) in plan.%n", plan.getSteps().size());
        return 0;
    }
}
