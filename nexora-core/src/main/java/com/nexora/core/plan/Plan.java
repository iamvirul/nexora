package com.nexora.core.plan;

import java.util.List;

public class Plan {

    private final List<Step> steps;

    public Plan(List<Step> steps) {
        this.steps = steps;
    }

    public List<Step> getSteps() {
        return steps;
    }
}
