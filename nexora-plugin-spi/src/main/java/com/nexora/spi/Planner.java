package com.nexora.spi;

import com.nexora.core.intent.Intent;
import com.nexora.core.plan.Plan;

/**
 * Strategy that turns an Intent into a Plan.
 *
 * The engine holds a list of planners in priority order. For each execution,
 * it calls canPlan() on each and delegates to the first that accepts the intent.
 * This lets plugins contribute domain-specific planners (rule engines, LLMs,
 * constraint solvers) that sit alongside the built-in rule-based planner.
 *
 * Implementations must be thread-safe — the same instance is called concurrently.
 */
public interface Planner {

    PlannerDescriptor descriptor();

    /**
     * Returns true if this planner is capable of handling the given intent.
     * Called before plan() — must be fast and side-effect-free.
     */
    @SuppressWarnings("unused")
    boolean canPlan(Intent intent, PlanningContext context);

    /**
     * Builds an execution plan for the intent.
     *
     * @throws PlanningException if planning fails unrecoverably.
     */
    @SuppressWarnings("unused")
    Plan plan(Intent intent, PlanningContext context);
}
