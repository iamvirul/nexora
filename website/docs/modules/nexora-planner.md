---
id: nexora-planner
title: nexora-planner
sidebar_position: 7
---

# nexora-planner

Converts an `Intent` into a `Plan`. Composed of a registry of step definitions and a chain of planner implementations.

## StepDefinition

Declares what a step does and when it participates in a plan.

```java
StepDefinition validateStep = new StepDefinition(
    "validate",                          // step ID (unique in the plan)
    "validate_order",                    // capability ID to invoke
    goal -> goal.contains("order"),      // predicate: does this step apply to the goal?
    Map.of(
        "orderId", InputBinding.fromContext("orderId"),   // from intent context
        "region",  InputBinding.literal("EU")            // static value
    ),
    "validationResult",                  // output key stored in execution context
    Set.of(),                            // dependsOn (empty = no dependencies)
    null,                                // retryPolicyId (null = default policy)
    Duration.ofSeconds(10)              // per-step timeout
);
```

### Full constructor with compensation

```java
new StepDefinition(
    "charge", "charge_card",
    goal -> goal.contains("order"),
    inputs, "chargeResult",
    Set.of("validate"),
    "payment-retry",
    Duration.ofSeconds(5),
    "refund_card"                        // compensateCapabilityId for saga rollback
);
```

---

## PlanRegistry

Holds all registered `StepDefinition` objects and is queried by the planner to select which steps apply to a given intent.

```java
NexoraEngine engine = NexoraEngine.builder()
    .withStepDefinition(validateStep)
    .withStepDefinition(chargeStep)
    .withStepDefinition(receiptStep)
    .build();
```

---

## RulePlanner

The built-in keyword-based planner. For each `StepDefinition` whose `matcher` predicate returns `true` for the intent goal, the step is included in the plan. Dependency ordering from `dependsOn` is preserved.

```java
// These two steps both match "process_order":
// validate: goal -> goal.contains("order")   => true
// charge:   goal -> goal.contains("order")   => true

// Resulting plan: validate (no deps) + charge (depends on validate)
```

---

## CompositePlanner

Chains multiple `Planner` implementations. Tries each planner in priority order (highest first) and returns the first non-empty plan.

```java
// Plugin planners are inserted before the built-in RulePlanner.
// An LLM plugin with priority=100 will be tried before RulePlanner (priority=0).
```

### Implementing a custom Planner

Implement `com.nexora.spi.Planner` and contribute it via a plugin:

```java
public class LlmPlanner implements Planner {

    @Override
    public Optional<Plan> plan(Intent intent, PlanRegistry registry) {
        String prompt = buildPrompt(intent, registry.allDefinitions());
        List<String> stepIds = llmClient.planSteps(prompt);

        if (stepIds.isEmpty()) return Optional.empty();

        List<Step> steps = stepIds.stream()
            .map(registry::getDefinition)
            .filter(Optional::isPresent)
            .map(opt -> opt.get().toStep())
            .toList();

        return Optional.of(new Plan(steps));
    }

    @Override
    public int priority() { return 100; }
}
```

Return `Optional.empty()` to pass control to the next planner in the chain.
