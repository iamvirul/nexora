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
| `POST /api/execute` | Trigger an execution remotely |
| `GET /health/ready` | Check health of all capabilities (returns 503 if any circuit is OPEN/HALF_OPEN) |
| `GET /api/webhook-deliveries/{id}` | Audit log of webhook delivery attempts for an execution |
| `GET /api/dead-letters` | List dead letter queue entries (paginated via `?page=` and `?size=`, filterable by `?state=PENDING\|RESOLVED\|REPLAYED\|ALL`) — *Unreleased* |
| `POST /api/dead-letters/{id}/replay` | Create a new execution from a dead letter — *Unreleased* |
| `POST /api/dead-letters/{id}/resolve` | Mark a dead letter as resolved — *Unreleased* |

> **Note**: The `/health/ready`, `/api/webhook-deliveries`, and `/api/dead-letters` endpoints are currently **Unreleased**.

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

## Webhook Callbacks (Unreleased version)

Nexora allows you to register webhook URLs to be notified asynchronously when an execution reaches a terminal state (`COMPLETED`, `FAILED`, or `TIMED_OUT`). This is particularly useful when triggering executions remotely via the API and awaiting their outcome.

To use webhooks securely, configure an HMAC-SHA256 signature secret in your `nexora.json` or via the `NEXORA_WEBHOOK_SECRET` environment variable:

```json
{
  "webhookSecret": "your-secure-secret-here",
  "steps": []
}
```

Nexora will dispatch a JSON payload to your endpoint with the execution outcome. It signs the payload using the configured secret and passes the signature in the `nexora-signature` HTTP header for validation. Delivery attempts are persisted in the `nexora_webhook_deliveries` database table and can be queried for auditability via the API.

## Dead Letter Queue API (Unreleased version)

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
- `nexora_plan_duration_seconds`: histogram by status (completed/failed)
- `nexora_step_started_total`, `nexora_step_completed_total`, `nexora_step_failed_total`: all by capability ID
- `nexora_step_duration_seconds`: histogram by capability ID and terminal status
- `nexora_plan_amendments_total`: by amendment type (ADD_STEP, SKIP_STEP, MODIFY_INPUT)
- `nexora_active_executions`: current in-flight count

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
