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

This exposes four endpoints with no external dependencies:

| Endpoint | What it serves |
|----------|---------------|
| `GET /` | Live process UI showing active executions, step timelines, and plan amendments |
| `GET /metrics` | Prometheus text format scrape endpoint |
| `GET /api/process` | Raw process snapshot as JSON |
| `POST /api/execute` | Trigger an execution remotely; returns `202` with the new `executionId` |
| `GET /health/live` | Liveness probe. Returns `200` while the process is serving HTTP |
| `GET /health/ready` | Readiness probe. Returns `200` when persistence, plugins, and the executor are all UP, `503` with a per-check breakdown otherwise |
| `GET /health` | Summary: overall status, Nexora version, readiness checks, and capability circuit states (informational) |
| `GET /api/webhook-deliveries/{id}` | Audit log of webhook delivery attempts for an execution |
| `GET /api/dead-letters` | List dead letter queue entries (paginated via `?page=` and `?size=`, filterable by `?state=PENDING\|RESOLVED\|REPLAYED\|ALL`) |
| `POST /api/dead-letters/{id}/replay` | Create a new execution from a dead letter |
| `POST /api/dead-letters/{id}/resolve` | Mark a dead letter as resolved |
| `DELETE /api/executions/{id}` | Cancel a running execution (`200` cancelled, `404` unknown, `409` already finished). See [Execution Cancellation](concepts/execution-cancellation) |
| `GET /api/schedules` | List all cron schedules |
| `POST /api/schedules` | Register a new cron schedule |
| `DELETE /api/schedules/{id}` | Cancel a cron schedule |

> **Note**: The `/api/webhook-deliveries`, `/api/dead-letters`, and `/api/schedules` endpoints were added in v0.2.0. `/health/live` and `/health` were added in v0.3.0, when `/health/ready` changed from capability circuit state to dependency readiness (see [Health Checks](#health-checks)).

Example execute request with webhook callback:

```bash
curl -X POST http://localhost:9464/api/execute \
  -H "content-type: application/json" \
  -d '{
        "goal": "process order payment notification",
        "context": {"orderId": "ORD-99"},
        "webhookUrl": "https://your-api.com/webhooks/nexora",
        "webhookEvents": ["COMPLETED", "FAILED", "TIMED_OUT"]
      }'
```

## Health Checks

The three health endpoints need no authentication, so kubelet probes and load balancers can call them directly.

```bash
curl http://localhost:9464/health/ready
# 200 {"status":"UP","checks":{"persistence":"UP","plugins":"UP","executor":"UP"}}
# 503 {"status":"DOWN","checks":{"persistence":"DOWN","plugins":"UP","executor":"UP"}}
```

| Check | DOWN when |
|-------|-----------|
| `persistence` | The execution store connection fails validation (2s timeout). Reported UP when persistence is disabled. |
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
curl http://localhost:9464/api/schedules

# register a nightly schedule
curl -X POST http://localhost:9464/api/schedules \
  -H "content-type: application/json" \
  -d '{"cronExpression":"0 0 * * *","goal":"nightly cleanup","missedFirePolicy":"FIRE_ONCE"}'

# cancel a schedule
curl -X DELETE http://localhost:9464/api/schedules/<id>
```

See [Cron Scheduling](concepts/cron-scheduling) for the full reference including missed-fire policies and events.

---

## Dead Letter Queue API

Permanently failed executions are captured in the dead letter queue. See [Dead Letter Queue](concepts/dead-letter-queue) for full documentation.

```bash
# list PENDING (default)
curl http://localhost:9464/api/dead-letters

# list all states with pagination
curl "http://localhost:9464/api/dead-letters?state=ALL&page=0&size=10"

# filter by state
curl "http://localhost:9464/api/dead-letters?state=RESOLVED&page=0&size=20"

# replay a dead letter (creates a new execution from the original goal + context)
curl -X POST http://localhost:9464/api/dead-letters/<id>/replay

# resolve with a reason
curl -X POST http://localhost:9464/api/dead-letters/<id>/resolve \
  -H "content-type: application/json" \
  -d '{"reason":"investigated and closed"}'

# resolve without a reason (reason field is optional)
curl -X POST http://localhost:9464/api/dead-letters/<id>/resolve
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
