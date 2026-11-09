# Nexora Helm chart

Runs `nexora observe` (the execution API, dashboard, live stream and Prometheus metrics) on Kubernetes.

## Install

```bash
kubectl create namespace nexora

# The API key every /api request and the dashboard need (at least 32 characters).
kubectl --namespace nexora create secret generic nexora-api-key \
  --from-literal=api-key="$(openssl rand -base64 36)"

helm install nexora deploy/helm/nexora --namespace nexora
```

Then open the dashboard. Forward both ports: the dashboard's live stream connects to the second one.

```bash
kubectl --namespace nexora port-forward svc/nexora 9464 9465
open http://localhost:9464
```

Sign in with the key from the Secret:

```bash
kubectl --namespace nexora get secret nexora-api-key -o jsonpath='{.data.api-key}' | base64 -d
```

## Image

The chart pulls `ghcr.io/iamvirul/nexora:<appVersion>`. Each `v*` release tag publishes it, so the first published image is the 0.5.0 release. Before that, or to run unreleased code, build the image and point the chart at it:

```bash
mvn -pl nexora-cli -am package -DskipTests
docker build -t my-registry.example.com/nexora:dev .
docker push my-registry.example.com/nexora:dev

helm install nexora deploy/helm/nexora --namespace nexora \
  --set image.repository=my-registry.example.com/nexora --set image.tag=dev
```

On a local kind cluster, `kind load docker-image nexora:dev` replaces the push; add `--set image.pullPolicy=Never`.

## What it creates

| Resource | When |
|----------|------|
| Deployment | Always. One container, non-root (uid 10001), read-only root filesystem, no capabilities |
| Service | Always. Port 9464 (HTTP: API, dashboard, `/metrics`, `/health/*`) and 9465 (the dashboard's WebSocket) |
| ConfigMap | Always. `nexora.json`, built from `config` |
| ServiceAccount | `serviceAccount.create` (default). No API token is mounted |
| PersistentVolumeClaim | `persistence.mode=h2` (default), unless `persistence.h2.existingClaim` is set. Kept on `helm uninstall` |
| Secret | Only for values given inline: `apiKey.value` or `persistence.jdbc.url` |
| HorizontalPodAutoscaler | `autoscaling.enabled`. Needs `persistence.mode=jdbc` |
| PodDisruptionBudget | `podDisruptionBudget.enabled`. Needs more replicas than `minAvailable`, or node drains would hang |
| ServiceMonitor | `serviceMonitor.enabled`. Needs the Prometheus Operator CRDs |

Probes: startup and liveness on `GET /health/live`, readiness on `GET /health/ready`, which goes down when the store is unreachable, its schema is behind, a plugin is not active, or the executor has stopped. Resources default to requests `cpu: 250m, memory: 512Mi` and limits `cpu: 1, memory: 1Gi`; the JVM sizes its heap to 75% of the memory limit.

## Persistence

| `persistence.mode` | Store | Replicas |
|--------------------|-------|----------|
| `h2` (default) | Embedded H2 file on a ReadWriteOnce PersistentVolumeClaim, mounted at `/data` | One. The Deployment uses the `Recreate` strategy so the old pod releases the volume before the new one starts |
| `jdbc` | An external PostgreSQL 15+ database | Any number, see [Scaling out](#scaling-out) |
| `none` | In memory | One: each pod would have its own history. History, replay, schedules and dead letters are lost on restart |

For PostgreSQL, put the whole JDBC URL, credentials included, in a Secret. URL-encode special characters in the password (`@` is `%40`, `/` is `%2F`, a space is `%20`):

```bash
kubectl --namespace nexora create secret generic nexora-db \
  --from-literal=jdbc-url='jdbc:postgresql://postgres:5432/nexora?user=nexora&password=...'

helm install nexora deploy/helm/nexora --namespace nexora \
  --set persistence.mode=jdbc --set persistence.jdbc.existingSecret=nexora-db
```

Replicas that start together on an empty database apply the schema migrations once; the others wait or retry briefly and then start.

## HTTPS

Give the chart a `kubernetes.io/tls` Secret, for example one cert-manager issues:

```bash
helm install nexora deploy/helm/nexora --namespace nexora \
  --set tls.enabled=true --set tls.existingSecret=nexora-tls
```

The server then serves HTTPS and WSS on both ports, and the probes switch to HTTPS. The key must be PKCS#8 or PKCS#1 RSA. cert-manager writes ECDSA keys as SEC1 by default, which the server refuses: set `privateKey.encoding: PKCS8` on the Certificate.

## Monitoring

```bash
helm install nexora deploy/helm/nexora --namespace nexora \
  --set serviceMonitor.enabled=true \
  --set serviceMonitor.labels.release=kube-prometheus-stack
```

Set `serviceMonitor.labels` to whatever your Prometheus selects ServiceMonitors by. With `tls.enabled`, the endpoint is scraped over HTTPS; pass the CA or server name through `serviceMonitor.tlsConfig`. Traces go to `otel.endpoint` (OTLP/HTTP), for example `http://otel-collector:4318`, under service name `nexora`. A span is recorded for each capability call (`capability.<id>`), so an execution whose plan has no steps sends no traces.

## Scaling out

More than one replica needs `persistence.mode=jdbc`, so every pod shares one store. Even then, each pod keeps some state in memory:

- dashboard sessions and user API keys issued at login,
- running executions, so a cancel request must reach the pod running the execution,
- rate-limit counters, so each pod allows the full rate.

So two replicas behave as two servers over one shared history, not as one server. A dashboard session or cancel that reaches the other pod fails. `service.sessionAffinity=ClientIP` keeps each client on one pod, which covers browsers and the CLI behind a single address. The chart refuses `replicaCount > 1` or `autoscaling.enabled` with `persistence.mode=h2`.

## Configuration

`config` becomes `nexora.json`: steps, plugins, retry, `persistence.pool`, `observe.rateLimit`. Do not set `executionStore`; `persistence.mode` picks the store. Behind an ingress or load balancer, list its addresses in `observe.rateLimit.trustedProxies` so clients are told apart by `X-Forwarded-For`.

```yaml
config:
  steps:
    - id: validate_order
      capabilityId: validate_order
      matchesGoalContains: order
  observe:
    rateLimit:
      trustedProxies: [10.0.0.10]
```

Dashboard users for username and password sign-in: create the users file with `nexora user add`, store it in a Secret, and set `users.existingSecret`.

All values, with comments, are in [values.yaml](values.yaml). The chart refuses settings that would deploy but not work: a missing API key, `h2` or `none` with more than one replica or the HPA, `jdbc` without a URL, TLS without a certificate, a PodDisruptionBudget that would block every drain, or an `executionStore` in `config`.

## Plain manifests

Not using Helm? [deploy/k8s](../../k8s) has the default rendering of this chart as plain YAML.
