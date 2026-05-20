package com.nexora.planner.engine;

import com.nexora.core.intent.Intent;
import com.nexora.core.plan.Plan;
import com.nexora.spi.Planner;
import com.nexora.spi.PlannerDescriptor;
import com.nexora.spi.PlanningContext;

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
