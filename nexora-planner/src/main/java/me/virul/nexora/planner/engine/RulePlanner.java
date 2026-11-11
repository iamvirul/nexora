package me.virul.nexora.planner.engine;

import me.virul.nexora.core.intent.Intent;
import me.virul.nexora.core.plan.Plan;
import me.virul.nexora.spi.Planner;
import me.virul.nexora.spi.PlannerDescriptor;
import me.virul.nexora.spi.PlanningContext;

/**
 * Built-in planner that matches steps by checking whether the intent goal
 * string contains each step definition's declared keyword.
 * Always returns true from canPlan() — it is the fallback of last resort.
 */
public final class RulePlanner implements Planner {

    private static final PlannerDescriptor DESCRIPTOR =
            new PlannerDescriptor("rule-planner", "Keyword-based rule planner", Integer.MIN_VALUE);

    private final PlannerEngine engine;

    public RulePlanner(PlannerEngine engine) {
        this.engine = engine;
    }

    @Override
    public PlannerDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public boolean canPlan(Intent intent, PlanningContext context) {
        return true;
    }

    @Override
    public Plan plan(Intent intent, PlanningContext context) {
        return engine.createPlan(intent);
    }
}
