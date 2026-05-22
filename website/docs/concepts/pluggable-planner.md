---
id: pluggable-planner
title: Pluggable Planner
sidebar_position: 1
---

# Pluggable planner

The planner that converts a goal string into a DAG is itself a plugin. Implement `Planner` and return it from `plannerProviders()` in your `NexoraPlugin`:

```java
public class MySmartPlanner implements Planner {

    @Override
    public PlannerDescriptor descriptor() {
        return new PlannerDescriptor("my-planner", "LLM-backed planner", 100);
    }

    @Override
    public boolean canPlan(Intent intent, PlanningContext context) {
        return intent.getGoal().length() > 20; // handle complex goals
    }

    @Override
    public Plan plan(Intent intent, PlanningContext context) {
        // use context.availableCapabilities() to see what's registered
        // build and return a Plan
    }
}
```

The engine tries planners in descending priority order. The built-in rule-based planner always sits last as the fallback. Registering a planner with priority 100 means it runs first; if `canPlan()` returns false, the next one is tried.

You can also register a planner directly without a plugin:

```java
NexoraEngine.builder()
    .withPlanner(new MySmartPlanner())
    .build();
```
