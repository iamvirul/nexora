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

## Declining after `canPlan()`

A planner that accepted an intent but then cannot produce a usable plan, for example because a remote model was unreachable or answered with something invalid, throws `PlanningDeclinedException` from `plan()`. The engine then tries the next planner in priority order, ending with the rule-based planner, instead of failing the execution. Any other `PlanningException` still fails planning.

```java
@Override
public Plan plan(Intent intent, PlanningContext context) {
    try {
        return askTheModel(intent, context);
    } catch (ModelUnavailableException e) {
        throw new PlanningDeclinedException("model unavailable", e);
    }
}
```

## LLM planner

[`nexora-planner-llm`](../llm-planner/overview) is a ready-made planner plugin that plans goals with Anthropic, OpenAI, Gemini or any model on OpenRouter, validates the model's answer, and falls back to the rule-based planner when the answer cannot be used.
