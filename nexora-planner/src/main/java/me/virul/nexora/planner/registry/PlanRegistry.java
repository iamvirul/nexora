package me.virul.nexora.planner.registry;

import java.util.ArrayList;
import java.util.List;
import me.virul.nexora.planner.model.StepDefinition;

public class PlanRegistry {

    private final List<StepDefinition> steps = new ArrayList<>();

    public void register(StepDefinition step) {
        steps.add(step);
    }

    public List<StepDefinition> getMatching(String intent) {
        return steps.stream().filter(s -> s.matches(intent)).toList();
    }
}
