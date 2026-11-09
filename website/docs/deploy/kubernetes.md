---
id: kubernetes
title: Deploying on Kubernetes
sidebar_label: Kubernetes
---

# Deploying on Kubernetes

Each release publishes a container image of `nexora observe`, `ghcr.io/iamvirul/nexora:<version>`, starting with 0.5.0, and the repository ships a Helm chart and plain manifests to run it. Until that release, or to run unreleased code, build the image yourself and set `image.repository` and `image.tag`; the [chart README](https://github.com/iamvirul/nexora/tree/main/deploy/helm/nexora#image) shows how.

| Path | For |
|------|-----|
| [`deploy/helm/nexora`](https://github.com/iamvirul/nexora/tree/main/deploy/helm/nexora) | Helm installs; every option, documented in its README and `values.yaml` |
| [`deploy/k8s`](https://github.com/iamvirul/nexora/tree/main/deploy/k8s) | `kubectl` or GitOps without Helm: the chart's default rendering |
| [`Dockerfile`](https://github.com/iamvirul/nexora/blob/main/Dockerfile) | Building the image yourself |

## Quick start

```bash
kubectl create namespace nexora
kubectl --namespace nexora create secret generic nexora-api-key \
  --from-literal=api-key="$(openssl rand -base64 36)"

helm install nexora deploy/helm/nexora --namespace nexora

kubectl --namespace nexora port-forward svc/nexora 9464 9465
```

Open `http://localhost:9464` and sign in with the key from the Secret. Forward both ports: the dashboard's live stream connects to the second.

## What the defaults give you

- One pod, running as uid 10001 with a read-only root filesystem, no capabilities and the `RuntimeDefault` seccomp profile. It meets the `restricted` Pod Security standard.
- An embedded H2 store on a 1Gi PersistentVolumeClaim, kept when the release is uninstalled.
- Startup and liveness probes on `/health/live`, a readiness probe on `/health/ready`.
- Requests `cpu: 250m, memory: 512Mi`, limits `cpu: 1, memory: 1Gi`. The JVM sizes its heap to 75% of the memory limit.
- The API key from Secret `nexora-api-key`, passed as `NEXORA_API_KEY`.

## PostgreSQL

Store the JDBC URL, credentials included, in a Secret, and switch the chart to it:

```bash
kubectl --namespace nexora create secret generic nexora-db \
  --from-literal=jdbc-url='jdbc:postgresql://postgres:5432/nexora?user=nexora&password=...'

helm upgrade --install nexora deploy/helm/nexora --namespace nexora \
  --set persistence.mode=jdbc --set persistence.jdbc.existingSecret=nexora-db
```

The server reads it from `NEXORA_JDBC_URL`. URL-encode special characters in the password. The URL is never logged.

## HTTPS, monitoring and tracing

- **HTTPS**: `--set tls.enabled=true --set tls.existingSecret=<a kubernetes.io/tls Secret>`. The key must be PKCS#8 or PKCS#1 RSA; for a cert-manager ECDSA certificate set `privateKey.encoding: PKCS8`.
- **Prometheus**: `--set serviceMonitor.enabled=true`, with `serviceMonitor.labels` set to what your Prometheus selects.
- **Tracing**: `--set otel.endpoint=http://otel-collector:4318`. Spans arrive under service `nexora`, one per capability call, so a plan with no steps sends none.

## Running more than one replica

More than one replica needs PostgreSQL (`persistence.mode=jdbc`); the chart refuses it with H2. Each pod still keeps dashboard sessions, running executions, rate-limit counters and cron schedules in its own memory, so replicas share history but not sessions: a dashboard request or cancel that lands on another pod fails, and a schedule fires only on the pod that registered it until the others restart. Use `service.sessionAffinity=ClientIP` so each client stays on one pod, and treat the HPA and PodDisruptionBudget (both off by default) as options for that setup.

Replicas starting together on an empty database apply the schema migrations once; the rest wait or retry briefly, then start.
