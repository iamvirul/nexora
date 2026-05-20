package com.nexora.planner.engine;

import com.nexora.core.intent.Intent;
import com.nexora.core.plan.Plan;
import com.nexora.spi.Planner;
import com.nexora.spi.PlannerDescriptor;
import com.nexora.spi.PlanningContext;
import com.nexora.spi.PlanningException;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Delegates planning to the highest-priority registered planner that accepts the intent.
 *
 * Plugin planners are sorted by PlannerDescriptor.priority (descending) and tried in order.
 * The built-in RulePlanner is always appended last as the fallback — it always returns
 * canPlan()=true, so at least one planner will always match.
 */
public final class CompositePlanner implements Planner {

    private static final PlannerDescriptor DESCRIPTOR =
            new PlannerDescriptor("composite-planner", "Priority-ordered composite planner");

    private final List<Planner> planners;

    public CompositePlanner(List<Planner> pluginPlanners, RulePlanner fallback) {
        Objects.requireNonNull(pluginPlanners);
        Objects.requireNonNull(fallback);
        List<Planner> ordered = new ArrayList<>(pluginPlanners);
        ordered.sort(Comparator.comparingInt(p -> -p.descriptor().priority()));
        ordered.add(fallback);
        this.planners = List.copyOf(ordered);
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
        for (Planner planner : planners) {
            if (planner.canPlan(intent, context)) {
                return planner.plan(intent, context);
            }
        }
        throw new PlanningException("No planner could handle intent: " + intent.getGoal());
    }

    public List<Planner> planners() {
        return planners;
    }
}
