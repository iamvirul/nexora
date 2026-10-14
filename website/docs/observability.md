---
id: observability
title: Observability
sidebar_position: 6
---

# Observability UI + Prometheus + Grafana

Start Nexora's built-in observability server:

```bash
java -jar nexora-cli/target/nexora.jar observe --port 9464
```

The server's full HTTP API is described by an OpenAPI 3.1 document at `GET /openapi.yaml`, browsable with Swagger UI at [`/docs`](http://localhost:9464/docs) (served from bundled assets, no external requests).

It exposes these endpoints with no external dependencies:

| Endpoint | What it serves |
|----------|---------------|
| `GET /` | Live process UI showing active executions, step timelines, and plan amendments |
| `GET /metrics` | Prometheus text format scrape endpoint |
| `GET /openapi.yaml` | OpenAPI 3.1 description of every endpoint |
| `GET /docs` | Swagger UI for that document |
| `GET /api/process` | Raw process snapshot as JSON |
| `POST /api/execute` | Trigger an execution remotely; returns `202` with the new `executionId` |
| `GET /health/live` | Liveness probe. Returns `200` while the process is serving HTTP |
| `GET /health/ready` | Readiness probe. Returns `200` when persistence, plugins, and the executor are all UP, `503` with a per-check breakdown otherwise |
| `GET /health` | Summary: overall status, Nexora version, readiness checks, and capability circuit states (informational) |
| `GET /api/webhook-deliveries/{id}` | Audit log of webhook delivery attempts for an execution |
| `GET /api/dead-letters` | List dead letter queue entries (paginated via `?page=` and `?size=`, filterable by `?state=PENDING\|RESOLVED\|REPLAYED\|ALL`) |
| `POST /api/dead-letters/{id}/replay` | Create a new execution from a dead letter |
| `POST /api/dead-letters/{id}/resolve` | Mark a dead letter as resolved |
| `GET /api/executions` | Execution history, newest first, cursor-paginated (see [Execution History API](#execution-history-api)); filter by `status`, `goal`, `from`, `to`, `limit` |
| `GET /api/executions/{id}` | One execution with its step records (`404` if unknown) |
| `DELETE /api/executions/{id}` | Cancel a running execution (`200` cancelled, `404` unknown, `409` already finished). See [Execution Cancellation](concepts/execution-cancellation) |
| `POST /api/executions/{id}/replay` | Replay a failed, timed-out, cancelled or compensated execution (`202` with the new `executionId`, `409` otherwise). See [Execution Replay](concepts/execution-replay) |
| `GET /api/schedules` | List all cron schedules |
| `POST /api/schedules` | Register a new cron schedule |
| `DELETE /api/schedules/{id}` | Cancel a cron schedule |

> **Note**: The `/api/webhook-deliveries`, `/api/dead-letters`, and `/api/schedules` endpoints were added in v0.2.0. `/health/live` and `/health` were added in v0.3.0, when `/health/ready` changed from capability circuit state to dependency readiness (see [Health Checks](#health-checks)).

Example execute request with webhook callback:

```bash
curl -H "Authorization: Bearer $NEXORA_API_KEY" -X POST http://localhost:9464/api/execute \
  -H "content-type: application/json" \
  -d '{
        "goal": "process order payment notification",
        "context": {"orderId": "ORD-99"},
        "webhookUrl": "https://your-api.com/webhooks/nexora",
        "webhookEvents": ["COMPLETED", "FAILED", "TIMED_OUT"]
      }'
```

## Authentication

Every `/api/*` route requires an API key, and so does the live WebSocket stream on `port + 1`. `/metrics`, `/health/*`, the dashboard page, `/openapi.yaml` and `/docs` stay public, because scrapers and probes cannot carry credentials.

**First start.** `nexora observe` generates a key, prints it once, and saves only its SHA-256 digest to `~/.nexora/observe-api-key.sha256` (owner-only permissions; change the path with `--api-key-file`). Later starts read the digest and cannot show the key again.

**CI and containers.** Set `NEXORA_API_KEY` (at least 32 characters) and nothing is generated, printed or written. `--api-key` also works, but flags are visible to other users in the process list.

```bash
export NEXORA_API_KEY="$(openssl rand -base64 32)"
nexora observe
curl -H "Authorization: Bearer $NEXORA_API_KEY" http://localhost:9464/api/process
```

**Dashboard.** The UI signs in with a username and password, or with the key, and receives an `HttpOnly; SameSite=Strict` session cookie (12 hours), so no secret is ever stored in the browser. A cookie-authenticated request that changes state must also come from the dashboard's own origin.

**Users and passwords.** For people, add dashboard accounts with `nexora user add <username>` (also `passwd`, `remove`, `list`). Passwords are hashed with PBKDF2-HMAC-SHA256 (600,000 iterations, per-user salt) into `~/.nexora/observe-users`, owner-only (override with `--users-file`); they must be 12 to 128 characters. A correct password mints a **user API key** (`nxu_...`): valid for 12 hours, accepted everywhere the server's key is, and recorded under the user's name in the audit log. The dashboard receives it as the `HttpOnly` session cookie, so page script never sees it; `nexora login` receives it from `POST /api/keys` and saves it to `~/.nexora/credentials` for `nexora cancel` and `nexora replay`. Removing a user, changing their password, signing out, or restarting the server revokes their keys, and account changes apply to a running server without a restart. Five wrong passwords lock a username for 15 minutes, and each client gets at most 20 attempts a minute; unknown usernames are treated exactly like real ones, so responses never reveal whether an account exists. The server's own key keeps working throughout, as the break-glass login.


**CLI.** `nexora cancel` and `nexora replay` use `--api-key`, else `NEXORA_API_KEY`, else the user API key saved by `nexora login`.

**Payment pipeline demo.** `tests/payment-pipeline` uses the same auth. It prints a key for each run and saves nothing, or uses `NEXORA_API_KEY`.

**Rotation.** Restart with `nexora observe --rotate-api-key` to print a new key and replace the stored digest; the old key stops working immediately. With `NEXORA_API_KEY`, change the variable and restart.

A missing or wrong key gets `401` with `{"error":{"code":"UNAUTHORIZED","message":"..."}}`, before any other check. Until TLS lands ([#40](https://github.com/iamvirul/nexora/issues/40)) the key crosses the network in clear, so keep the server on localhost or a trusted network, or put a TLS proxy in front of it.

## Health Checks

The three health endpoints need no authentication, so kubelet probes and load balancers can call them directly.

```bash
curl http://localhost:9464/health/ready
# 200 {"status":"UP","checks":{"persistence":"UP","migrations":"UP","plugins":"UP","executor":"UP"}}
# 503 {"status":"DOWN","checks":{"persistence":"DOWN","migrations":"DOWN","plugins":"UP","executor":"UP"}}
```

| Check | DOWN when |
|-------|-----------|
| `persistence` | The execution store connection fails validation (2s timeout). Reported UP when persistence is disabled. |
| `migrations` | A schema migration is recorded as failed in `nexora_schema_history`, or the database schema is older than this release expects (for example after restoring an old backup). Reported UP when persistence is disabled. |
| `plugins` | Any registered plugin is not `ACTIVE` (never activated, stuck initializing, or failed). |
| `executor` | The engine's executor has been shut down. |

Capability circuit breaker state does not affect readiness. An OPEN circuit means a downstream dependency is failing, and taking every instance out of rotation for that would turn a partial outage into a full one. Circuit states are reported under `capabilities` in `GET /health`.

Kubernetes probe configuration:

```yaml
livenessProbe:
  httpGet:
    path: /health/live
    port: 9464
  periodSeconds: 10
  failureThreshold: 3
readinessProbe:
  httpGet:
    path: /health/ready
    port: 9464
  periodSeconds: 5
  timeoutSeconds: 3
  failureThreshold: 2
```

The `nexora_ready` gauge mirrors `/health/ready` (1 = ready, 0 = not ready) and drives the `NexoraNotReady` alert in `observability/prometheus/alerts.yml`.

## Execution History API

Read-only access to past and running executions, backed by the persistence store (`executionStore` in `nexora.json`). Without a store these routes return `503`.

> **Authentication**: these endpoints return execution goals and context, so they require the API key. See [Authentication](#authentication).

### List executions

```bash
curl -H "Authorization: Bearer $NEXORA_API_KEY" "http://localhost:9464/api/executions?status=FAILED,CANCELLED&limit=20"
```

```json
{
  "data": [
    {
      "executionId": "4f3a1c2d-...",
      "traceId": "9b1e...",
      "goal": "process payment",
      "state": "FAILED",
      "startedAt": "2026-10-01T10:00:00.123Z",
      "completedAt": "2026-10-01T10:00:01.456Z",
      "durationMs": 1333
    }
  ],
  "cursor": "MjAyNi0xMC0wMVQxMDowMDowMC4xMjNafDRmM2ExYzJkLS4uLg",
  "total": 42
}
```

| Parameter | Description |
|-----------|-------------|
| `status` | One or more states, comma-separated: `RUNNING`, `COMPLETED`, `FAILED`, `TIMED_OUT`, `CANCELLED`, `COMPENSATING`, `COMPENSATED` |
| `goal` | Case-insensitive substring of the goal (max 200 characters) |
| `from` | Inclusive lower bound on `startedAt`, ISO-8601 with an offset (`2026-10-01T10:00:00Z`). Encode `+` as `%2B`: `2026-10-01T15:30:00%2B05:30` |
| `to` | Exclusive upper bound on `startedAt`, same format as `from` |
| `limit` | Page size, 1 to 100 (default 20) |
| `cursor` | The `cursor` value from the previous page |

Results are ordered by `startedAt` descending. Pass the returned `cursor` to get the next page; it is `null` on the last page. `total` counts every execution matching the filters, not just this page.

Pagination is keyset-based (the cursor encodes `startedAt` and `executionId`), so pages stay consistent while new executions are written. Keep the same filters when following a cursor. Invalid parameters return `400` with an `error` message.

List rows are summaries. Use the detail route for context and steps.

The observe UI has an **Execution History** panel built on these routes: filter by status or goal, page with **Load more**, and click a row to see its steps.

### Get one execution

```bash
curl -H "Authorization: Bearer $NEXORA_API_KEY" http://localhost:9464/api/executions/4f3a1c2d-...
```

Returns the execution with its `context` and `steps` (state, timings, failure code and message for each step). Unknown ids return `404`; if the store cannot be queried the response is `500`, never a misleading `404`. Step outputs are not persisted yet ([#32](https://github.com/iamvirul/nexora/issues/32)).

---

## Webhook Callbacks

Nexora allows you to register webhook URLs to be notified asynchronously when an execution reaches a terminal state (`COMPLETED`, `FAILED`, or `TIMED_OUT`). This is particularly useful when triggering executions remotely via the API and awaiting their outcome.

To use webhooks securely, configure an HMAC-SHA256 signature secret in your `nexora.json` or via the `NEXORA_WEBHOOK_SECRET` environment variable:

```json
{
  "webhookSecret": "your-secure-secret-here",
  "steps": []
}
```

Nexora will dispatch a JSON payload to your endpoint with the execution outcome. It signs the payload using the configured secret and passes the signature in the `nexora-signature` HTTP header for validation. Delivery attempts are persisted in the `nexora_webhook_deliveries` database table and can be queried for auditability via the API.

## Cron Schedule API

Requires `executionStore` to be configured (see [CLI Reference](cli)). The dashboard includes a live **Cron Schedules** panel that auto-refreshes every 30 seconds.

```bash
# list all schedules
curl -H "Authorization: Bearer $NEXORA_API_KEY" http://localhost:9464/api/schedules

# register a nightly schedule
curl -H "Authorization: Bearer $NEXORA_API_KEY" -X POST http://localhost:9464/api/schedules \
  -H "content-type: application/json" \
  -d '{"cronExpression":"0 0 * * *","goal":"nightly cleanup","missedFirePolicy":"FIRE_ONCE"}'

# cancel a schedule
curl -H "Authorization: Bearer $NEXORA_API_KEY" -X DELETE http://localhost:9464/api/schedules/<id>
```

See [Cron Scheduling](concepts/cron-scheduling) for the full reference including missed-fire policies and events.

---

## Dead Letter Queue API

Permanently failed executions are captured in the dead letter queue. See [Dead Letter Queue](concepts/dead-letter-queue) for full documentation.

```bash
# list PENDING (default)
curl -H "Authorization: Bearer $NEXORA_API_KEY" http://localhost:9464/api/dead-letters

# list all states with pagination
curl -H "Authorization: Bearer $NEXORA_API_KEY" "http://localhost:9464/api/dead-letters?state=ALL&page=0&size=10"

# filter by state
curl -H "Authorization: Bearer $NEXORA_API_KEY" "http://localhost:9464/api/dead-letters?state=RESOLVED&page=0&size=20"

# replay a dead letter (creates a new execution from the original goal + context)
curl -H "Authorization: Bearer $NEXORA_API_KEY" -X POST http://localhost:9464/api/dead-letters/<id>/replay

# resolve with a reason
curl -H "Authorization: Bearer $NEXORA_API_KEY" -X POST http://localhost:9464/api/dead-letters/<id>/resolve \
  -H "content-type: application/json" \
  -d '{"reason":"investigated and closed"}'

# resolve without a reason (reason field is optional)
curl -H "Authorization: Bearer $NEXORA_API_KEY" -X POST http://localhost:9464/api/dead-letters/<id>/resolve
```

### Metrics exposed include

- `nexora_plan_started_total`, `nexora_plan_completed_total`, `nexora_plan_failed_total`
- `nexora_plan_duration_seconds`: histogram by status (completed/failed/timed_out/cancelled)
- `nexora_plan_cancelled_total`: cancelled executions (not counted as failures)
- `nexora_step_started_total`, `nexora_step_completed_total`, `nexora_step_failed_total`: all by capability ID
- `nexora_step_duration_seconds`: histogram by capability ID and terminal status
- `nexora_plan_amendments_total`: by amendment type (ADD_STEP, SKIP_STEP, MODIFY_INPUT)
- `nexora_active_executions`: current in-flight count
- `nexora_ready`: 1 when `/health/ready` reports UP, 0 otherwise

### Bring up Prometheus, Grafana, and Alertmanager

```bash
cd observability
docker compose up -d
```

- Prometheus: `http://localhost:9090`
- Alertmanager: `http://localhost:9093`
- Grafana: `http://localhost:3000` (login: `admin` / `admin`)

The Grafana dashboard and alert rules are provisioned automatically. Provisioned assets:

- `observability/prometheus/prometheus.yml`
- `observability/prometheus/alerts.yml`
- `observability/grafana/dashboards/nexora-overview.json`

### Attach observability directly to engine

You can also attach observability to an engine in your own application without the HTTP server:

```java
NexoraEngine engine = NexoraEngine.builder()...build();

try (NexoraObservability obs = NexoraObservability.attach(engine)) {
    engine.execute("process order", context).get();
    String metrics = obs.scrapePrometheus();           // Prometheus text format
    ProcessSnapshot snapshot = obs.processSnapshot();  // live execution state
}
```
