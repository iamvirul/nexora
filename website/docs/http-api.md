---
id: http-api
title: HTTP API
sidebar_position: 8
---

# HTTP API

The `nexora observe` server's HTTP API is described by an OpenAPI 3.1 document, maintained in the repository at `nexora-cli/src/main/resources/openapi.yaml`. The same file is served in two places:

- **On this site:** the [API reference](pathname:///api/) renders the version on `main`, and [`openapi.yaml`](pathname:///api/openapi.yaml) is the raw document for code generators and API clients.
- **By a running server:** `GET /openapi.yaml`, and Swagger UI at `/docs`, describe exactly the API that server serves. Use these when your server runs an older release than `main`.

## Versioning

The document's `info.version` is the version of the API contract, kept separately from the Nexora release version. It changes only when the API does, so a Nexora release with no API change keeps the API version it had, and the two numbers are not expected to match.

The API version follows semantic versioning:

| Change | Bump |
|--------|------|
| Removing or renaming a route, field or parameter; a new required field or header; a status code clients must now handle differently | Major (minor while below 1.0.0) |
| A new route, optional parameter, response field or status code | Minor |
| Descriptions and examples only | Patch |

Every pull request that changes an observe server route updates `openapi.yaml` (`OpenApiSpecTest` fails the build otherwise), bumps `info.version` once per release, and adds the change to the changelog below. CI checks that the current `info.version` has a section here.

The reference on this site is published from `main` whenever `openapi.yaml` or the site changes. A released version's document is the `openapi.yaml` at that release's tag, for example [`v0.4.0`](https://github.com/iamvirul/nexora/blob/v0.4.0/nexora-cli/src/main/resources/openapi.yaml).

## API changelog

### 0.5.0

Not yet in a Nexora release.

- **Added** `POST /api/plugins/{pluginId}/reload`, which swaps a plugin JAR on the running engine without a restart. Server API key only: user keys and dashboard sessions get `403`. Answers `400`, `404`, `409`, `422` and `503` with `{"error": {"code": "...", "message": "..."}}`; see the [reference](pathname:///api/) for each code.

### 0.4.0

First served by Nexora 0.4.0. The document shipped in that release still said `info.version: 0.3.0`.

- **Breaking:** every `/api` route and the live WebSocket require `Authorization: Bearer <key>` or the dashboard session cookie, and answer `401` with `{"error": {"code": "UNAUTHORIZED", ...}}` otherwise. `/metrics`, `/health/*`, the dashboard page, `/openapi.yaml` and `/docs` stay public.
- **Breaking:** a cookie-authenticated request that changes state must send an `Origin` matching the server.
- **Added** `POST /api/keys` (username and password for a user API key) and `GET`, `POST`, `DELETE /api/session` for the dashboard session.
- **Added** the `X-Tenant-ID` header. On a server started with `--multi-tenant`, it scopes every execution, history, dead letter, schedule and webhook route, and answers `400 TENANT_REQUIRED`, `400 INVALID_TENANT_ID` or `403 TENANT_FORBIDDEN`. Without the flag it is ignored.
- **Added** `429 RATE_LIMIT_EXCEEDED` with `Retry-After` on every authenticated `/api` route.
- **Added** step `output` and `outputTruncated` to execution details and the `/api/process` snapshot, and the `migrations` readiness check to `/health/ready` and `/health`.

### 0.3.0

First served by Nexora 0.3.0. The first published version of the document: executions, history, replay and cancel, webhook deliveries, dead letters, schedules, health probes, metrics, the process snapshot, and the docs routes.
