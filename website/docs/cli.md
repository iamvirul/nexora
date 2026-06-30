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
| `nexora dlq list` | List dead letter queue entries (default: PENDING) *(Unreleased)* |
| `nexora dlq replay <id>` | Replay a dead-lettered execution *(Unreleased)* |
| `nexora dlq resolve <id>` | Mark a dead letter as resolved *(Unreleased)* |
| `nexora schedule add` | Register a recurring cron-based execution *(Unreleased)* |
| `nexora schedule list` | List all schedules and their next fire time *(Unreleased)* |
| `nexora schedule remove <id>` | Cancel a schedule immediately *(Unreleased)* |

Pass `-c '{"key":"value"}'` to `run` to inject context values that steps can reference.

## Config file

By default Nexora looks for `nexora.json` in the working directory. Point to a different one with `--config`.

```json
{
  "executionStore": "./nexora-data",
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

## `nexora schedule`: Cron Scheduling (Unreleased)

Requires a persistence store (`executionStore` in `nexora.json` or `withExecutionStore(...)` in code).

### `schedule add`

```bash
nexora schedule add --goal "process payment" --cron "0 0 * * *"
nexora schedule add --goal "run report" --cron "0 9 * * 1-5" --policy FIRE_ONCE
```

| Option | Required | Default | Description |
|--------|----------|---------|-------------|
| `--goal` | Yes |, | Intent goal string |
| `--cron` | Yes |, | 5-field UNIX cron expression |
| `--policy` | No | `FIRE_ONCE` | Missed-fire policy: `SKIP`, `FIRE_ONCE`, `FIRE_ALL` |

Prints the schedule ID and the calculated next fire time.

### `schedule list`

```bash
nexora schedule list
nexora schedule list --active-only
```

Shows all schedules with their cron expression, next fire time, last fire time, policy, and active status.

### `schedule remove`

```bash
nexora schedule remove <schedule-id>
```

Cancels future firings immediately. The schedule is marked inactive in the store but not deleted, so the audit record is preserved.
