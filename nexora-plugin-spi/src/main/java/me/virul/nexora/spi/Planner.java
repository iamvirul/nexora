package me.virul.nexora.spi;

import me.virul.nexora.core.intent.Intent;
import me.virul.nexora.core.plan.Plan;

/**
 * Strategy that turns an Intent into a Plan.
 *
 * <p>The engine holds a list of planners in priority order. For each execution,
 * it calls canPlan() on each and delegates to the first that accepts the intent.
 * This lets plugins contribute domain-specific planners (rule engines, LLMs,
 * constraint solvers) that sit alongside the built-in rule-based planner.
 *
 * <p>Implementations must be thread-safe: the same instance is called concurrently.
 */
public interface Planner {

    /**
     * Identifies the planner and its priority.
     *
     * @return the descriptor; never null
     */
    PlannerDescriptor descriptor();

    /**
     * Returns true if this planner is capable of handling the given intent.
     * Called before plan(); must be fast and side-effect-free.
     *
     * @param intent  the intent to plan
     * @param context the capabilities and configuration available to the planner
     * @return {@code true} to be asked for the plan
     */
    @SuppressWarnings("unused")
    boolean canPlan(Intent intent, PlanningContext context);

    /**
     * Builds an execution plan for the intent.
     *
     * @param intent  the intent to plan
     * @param context the capabilities and configuration available to the planner
     * @return the plan; never null
     * @throws PlanningDeclinedException if this planner cannot plan the intent after all, so the next
     *                                   planner should be tried
     * @throws PlanningException if planning fails unrecoverably.
     */
    @SuppressWarnings("unused")
    Plan plan(Intent intent, PlanningContext context);
}
