package com.nexora.planner.registry;

import com.nexora.planner.model.StepDefinition;
import java.util.ArrayList;
import java.util.List;

public class PlanRegistry {

    private final List<StepDefinition> steps = new ArrayList<>();

    public void register(StepDefinition step) {
        steps.add(step);
    }

    public List<StepDefinition> getMatching(String intent) {
        return steps.stream().filter(s -> s.matches(intent)).toList();
    }
}
