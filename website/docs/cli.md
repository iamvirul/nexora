---
id: cli
title: CLI & Configuration
sidebar_position: 7
---

# CLI & Configuration

```
nexora [--config <file>] <command>
```

| Command | What it does |
|---------|-------------|
| `nexora run -g "<goal>"` | Execute an intent and stream step results |
| `nexora plan -g "<goal>"` | Dry run: show the DAG without executing anything |
| `nexora caps` | List all registered capabilities |
| `nexora plugins` | List active plugins |
| `nexora observe` | Start UI/API/metrics server for live process observability |
| `nexora demo` | Run the built-in feature demo |
| `nexora dlq list` | List dead letter queue entries (default: PENDING) — *Unreleased* |
| `nexora dlq replay <id>` | Replay a dead-lettered execution — *Unreleased* |
| `nexora dlq resolve <id>` | Mark a dead letter as resolved — *Unreleased* |

Pass `-c '{"key":"value"}'` to `run` to inject context values that steps can reference.

## Config file

By default Nexora looks for `nexora.json` in the working directory. Point to a different one with `--config`.

```json
{
  "steps": [
    {
      "id": "validate_order",
      "capabilityId": "validate_order",
      "matchesGoalContains": "order"
    },
    {
      "id": "charge_card",
      "capabilityId": "charge_card",
      "matchesGoalContains": "payment"
    }
  ],
  "retry": {
    "maxAttempts": 3,
    "initialDelayMs": 200,
    "multiplier": 2.0,
    "maxDelayMs": 10000
  }
}
```

A step is included in the plan when its `matchesGoalContains` string appears in the goal. Dependencies between steps are declared in code using `StepDefinition`'s `dependsOn` set.
