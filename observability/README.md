# Nexora Observability Stack

This folder contains a ready-to-run Prometheus + Grafana + Alertmanager setup for Nexora.

## 1) Start Nexora observability server

From the project root:

```bash
java -jar nexora-cli/target/nexora.jar observe --port 9464
```

Endpoints:

- UI: `http://localhost:9464/`
- Metrics: `http://localhost:9464/metrics`
- Process API: `http://localhost:9464/api/process`
- Execute API: `http://localhost:9464/api/execute`

Example execute request:

```bash
curl -X POST http://localhost:9464/api/execute \
  -H "content-type: application/json" \
  -d '{"goal":"process order payment notification","context":{"orderId":"ORD-99"}}'
```

## 2) Start Prometheus and Grafana

```bash
cd observability
docker compose up -d
```

- Prometheus: `http://localhost:9090`
- Alertmanager: `http://localhost:9093`
- Grafana: `http://localhost:3000` (admin/admin)

## Included Assets

- Prometheus scrape config: `prometheus/prometheus.yml`
- Alert rules: `prometheus/alerts.yml`
- Grafana datasource provisioning: `grafana/provisioning/datasources/datasource.yml`
- Grafana dashboard provisioning: `grafana/provisioning/dashboards/dashboards.yml`
- Dashboard JSON: `grafana/dashboards/nexora-overview.json`

## Notes

- The Prometheus target is `host.docker.internal:9464`.
- `docker-compose.yml` includes `host-gateway` mapping for Linux compatibility.
- If you change the observe command port, update `prometheus/prometheus.yml` target accordingly.
