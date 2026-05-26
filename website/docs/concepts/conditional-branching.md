---
id: conditional-branching
title: Conditional Branching
sidebar_position: 5
---

# Conditional Branching (Unreleased version)

:::info Unreleased Version
This feature is implemented in the unreleased development version of Nexora.
:::

Nexora provides built-in support for conditional branching, allowing you to dynamically skip or execute steps based on real-time execution context variables or outputs of previous steps. This provides dynamic flow control without having to write custom complex logic inside your capabilities.

## Step Conditions

You can define a `StepCondition` for any step in your plan. Just before the step is dispatched for execution, the `DagStepScheduler` evaluates the `StepCondition` against the current `ExecutionContext`. 

If the condition evaluates to `false`, the step is marked as `SKIPPED`. Downstream steps that depend on this skipped step will proceed gracefully, but if they rely on the output of the skipped step, they might fail depending on their implementation.

## Condition Types

Nexora ships with several built-in condition types:

1. **ContextValueEquals**: Checks if a value inside the intent context strictly equals a given value.
2. **StepOutputEquals**: Checks if a previous step returned a specific value in its output map.
3. **StepOutputPresent**: Checks if a previous step yielded a specific field in its output map.

### Logical Operators

You can compose complex rules using logical operators:
- **And**: Evaluates to true only if all child conditions evaluate to true.
- **Or**: Evaluates to true if at least one child condition evaluates to true.
- **Not**: Inverts the result of a child condition.

## JSON Configuration Example

If you are using the Nexora CLI or `CliConfig` module, you can easily define conditions in your `nexora.json`:

```json
{
  "steps": [
    {
      "id": "fetch_data",
      "capabilityId": "http_get",
      "matchesGoalContains": "get-data"
    },
    {
      "id": "process_data",
      "capabilityId": "transform",
      "matchesGoalContains": "get-data",
      "dependsOn": ["fetch_data"],
      "condition": {
        "type": "stepOutputEquals",
        "stepId": "fetch_data",
        "field": "status",
        "value": 200
      }
    }
  ]
}
```

In this example, `process_data` will only run if `fetch_data` completes and returns an output payload where `status` is equal to `200`. Note that `dependsOn` is specified to ensure the condition evaluates only after the producer step runs.

## Java API Example

You can also construct conditions dynamically via the `StepDefinition.Builder` when embedding Nexora into your own applications:

```java
import com.nexora.core.plan.condition.ContextValueEquals;
import com.nexora.planner.model.StepDefinition;

StepDefinition step = StepDefinition.builder("send_email", "send_email_cap")
    .withMatcher(goal -> goal.contains("email"))
    .withCondition(new ContextValueEquals("intent.context.send_email_enabled", true))
    .build();
```

## Dry-run Visualization

You can visually verify how your conditions will execute during a dry-run using the Nexora CLI:

```bash
nexora plan --goal "get-data"
```

The CLI will run a dummy context and print `(eval: false)` or `(eval: true)` indicating how the conditions will resolve out of the box!
