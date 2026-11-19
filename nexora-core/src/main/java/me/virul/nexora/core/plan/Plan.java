package me.virul.nexora.core.plan;

import java.util.List;

/** The steps a planner produced for an intent. The executor runs them as a DAG ordered by their dependencies. */
public class Plan {

    private final List<Step> steps;

    /**
     * Creates a plan.
     *
     * @param steps the steps; the list is kept as given, not copied
     */
    public Plan(List<Step> steps) {
        this.steps = steps;
    }

    /**
     * The steps of this plan.
     *
     * @return the list passed to the constructor
     */
    public List<Step> getSteps() {
        return steps;
    }
}
