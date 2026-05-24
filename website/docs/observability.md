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

> **Note**: The `/health/ready` endpoint is currently **Unreleased**.

Example execute request:

```bash
curl -X POST http://localhost:9464/api/execute \
  -H "content-type: application/json" \
  -d '{"goal":"process order payment notification","context":{"orderId":"ORD-99"}}'
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
