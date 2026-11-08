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
| `nexora plugins reload <pluginId> --jar <path>` | Swap a plugin for a new JAR inside the observe server, without a restart |
| `nexora observe` | Start UI/API/metrics server for live process observability |
| `nexora demo` | Run the built-in feature demo |
| `nexora dlq list` | List dead letter queue entries (default: PENDING) |
| `nexora dlq replay <id>` | Replay a dead-lettered execution |
| `nexora dlq resolve <id>` | Mark a dead letter as resolved |
| `nexora schedule add` | Register a recurring cron-based execution |
| `nexora schedule list` | List all schedules and their next fire time |
| `nexora schedule remove <id>` | Cancel a schedule immediately |
| `nexora cancel <executionId>` | Cancel an execution running in the observe server |
| `nexora replay <executionId>` | Replay a failed, timed-out, cancelled or compensated execution through the observe server |
| `nexora user add <username>` | Create a dashboard user, `--tenant <id>` to put it in a tenant (also `passwd`, `remove`, `tenant`, `list`) |
| `nexora login` | Sign in with a username and password; saves a user API key for `cancel` / `replay` |
| `nexora logout` | Revoke and delete the saved user API key |
| `nexora tls-gen` | Write a development CA and server certificate for HTTPS on localhost |

Pass `-c '{"key":"value"}'` to `run` to inject context values that steps can reference.

## Tenants for `nexora observe`

| Option | What it does |
|--------|-------------|
| `nexora observe --multi-tenant` | Scope every request and the live stream to one tenant. Users reach only their own tenant; the server API key must send `X-Tenant-ID` |
| `nexora user add <name> --tenant <id>` | Create a user in a tenant (default: `default`) |
| `nexora user tenant <name> <id>` | Move a user to another tenant; their sessions end |
| `nexora cancel/replay ... --tenant <id>` | Send `X-Tenant-ID`, needed with the server API key on a multi-tenant server |
| `nexora dlq ... --tenant <id>` | List, replay or resolve only that tenant's dead letters (default: every tenant) |
| `nexora schedule ... --tenant <id>` | Add schedules for that tenant; list and remove only its schedules (default: every tenant) |

See [Multi-tenancy](concepts/multi-tenancy.md).

## HTTPS for `nexora observe`

| Option | What it does |
|--------|-------------|
| `--tls-cert <file>` | PEM certificate, optionally followed by its chain (certbot's `fullchain.pem`). Needs `--tls-key` |
| `--tls-key <file>` | Unencrypted PEM private key for `--tls-cert` (certbot's `privkey.pem`) |
| `--keystore <file>` | PKCS12 keystore instead of the two PEM files; password from `NEXORA_KEYSTORE_PASSWORD` or `--keystore-password` |

`nexora tls-gen [--out ./certs] [--host <name>]... [--days 365] [--force]` writes `ca.pem`, `server.pem` and `server-key.pem` for local use. `nexora login`, `logout`, `cancel` and `replay` take `--cacert <ca.pem>` to trust that CA. See [HTTPS (TLS)](concepts/tls.md).

## Config file

By default Nexora looks for `nexora.json` in the working directory. Point to a different one with `--config`.

```json
{
  "executionStore": "./nexora-data",
  "plugins": ["/opt/nexora/plugins/payments-1.2.0.jar"],
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
  },
  "persistence": {
    "pool": {
      "maximumPoolSize": 10,
      "minimumIdle": 2,
      "connectionTimeoutMs": 30000,
      "idleTimeoutMs": 600000
    },
    "stepOutputMaxBytes": 65536
  }
}
```

`persistence.pool` sizes the HikariCP connection pool behind `executionStore`. Every key is optional and the values above are the defaults (`minimumIdle` defaults to 2, or to `maximumPoolSize` if that is smaller). `connectionTimeoutMs` must be at least 250 and `idleTimeoutMs` must be 0 (never retire idle connections) or at least 10000; out-of-range values fail at startup.

`persistence.stepOutputMaxBytes` caps the output stored for each completed step, in UTF-8 JSON bytes (default 65536). A larger output is stored as a truncated `preview` and logged at `WARN`; the step still completes, and later steps still receive the whole output. Values from 1 to 255 fail at startup. Set it to `0` to store no outputs, for example when steps return data that must not be written to the database.

`plugins` lists plugin JARs to load and activate at startup, in order: put a plugin's required plugins before it. Relative paths resolve against the working directory. A JAR that fails to load stops startup.

A step is included in the plan when its `matchesGoalContains` string appears in the goal. Dependencies between steps are declared in code using `StepDefinition`'s `dependsOn` set.

## `nexora plugins reload`: Hot Reload

Swaps a plugin for a new version inside a running `nexora observe` server, without dropping executions:

```bash
export NEXORA_API_KEY=nxk_...
nexora plugins reload payments --jar /opt/nexora/plugins/payments-1.3.0.jar
# Reloaded plugin payments: 1.2.0 -> 1.3.0.
```

New executions use the new version as soon as the command returns. Steps already running on the old version finish on it, and the old version is shut down when the last of them returns. If the new JAR fails to load or initialise, nothing changes: the old version keeps serving and the command exits with code 1 and the reason.

The command calls `POST /api/plugins/{pluginId}/reload` with `{"jar": "<path>"}`. Because a reload runs code from the JAR inside the server:

- Only the server API key may reload (`--api-key` or `NEXORA_API_KEY`). User keys from `nexora login` and dashboard sessions get `403`, on single and multi-tenant servers alike, since a plugin serves every tenant.
- The path is read on the server. The command makes a relative path absolute against its own working directory, which only matches when both run on the same machine.
- The new JAR must be a `.jar` file in the same directory as the plugin's current JAR, after resolving symlinks. Copy the new version next to the old one, under a new file name, before reloading.
- The server must run with an `executionStore`; without one the route answers `503`.
- Only plugins loaded from a JAR (`plugins` in `nexora.json`) can be reloaded; a plugin registered in code answers `409`.

## `nexora schedule`: Cron Scheduling

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
