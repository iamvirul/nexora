---
id: rate-limiting
title: Rate limiting
---

# Rate limiting

`nexora observe` limits how many API requests each client can make, so one client cannot flood `/api/execute`, exhaust the server's threads or overwhelm the database. Limits are on by default.

| Limit | Default | Applies to |
|---|---|---|
| Global | 60 requests per minute per client | Every authenticated `/api` route |
| Execute | 10 requests per minute per client | `POST /api/execute`, on top of the global limit |

Each client gets that many requests per one-minute window, then a full window again; the eleventh `POST /api/execute` within a minute is refused. A refused execute does not count against the global limit.

Limits apply after authentication: a request without valid credentials gets `401` and spends nothing, so it cannot use up someone else's allowance. Health probes, `/metrics`, the dashboard page and the API docs are never limited, since a throttled liveness probe would get the server restarted. Sign-in (`POST /api/session`, `POST /api/keys`) has its own lockout, described in [Authentication](authentication.md).

## When a client is over the limit

```
HTTP/1.1 429 Too Many Requests
Retry-After: 42
Content-Type: application/json; charset=utf-8

{"error": {"code": "RATE_LIMIT_EXCEEDED", "message": "Too many requests; retry after 42 seconds", "retryAfterSeconds": 42}}
```

`Retry-After` is the number of seconds until the client's window refills, rounded up, so a client that waits exactly that long always gets through.

Every refusal is:

- logged at `WARN`, with the client IP, method, route and which limit it hit:
  ```
  Rate limit exceeded clientIp=203.0.113.7 method=POST endpoint=/api/execute limit=execute retryAfterSeconds=42
  ```
- counted in `nexora_rate_limit_violations_total{endpoint, client_ip}`. `endpoint` is the route (`/api/executions/`, not each execution id).

The `client_ip` label adds a Prometheus series for every address that is ever refused, and those series stay until the server restarts. A flood from many addresses therefore grows the server's memory and your Prometheus storage. Aggregate it away in queries (`sum by (endpoint) (rate(nexora_rate_limit_violations_total[5m]))`), drop it at scrape time with `metric_relabel_configs` and a `labeldrop` action if you do not need it, and use the `WARN` log lines to find individual clients.

## Configuration

In `nexora.json`:

```json
{
  "observe": {
    "rateLimit": {
      "enabled": true,
      "globalRpm": 60,
      "executeRpm": 10,
      "trustedProxies": []
    }
  }
}
```

Every key is optional; the values above are the defaults. Limits below 1 fail at startup.

Set `"enabled": false` when a reverse proxy or API gateway already rate limits: the server then applies no limit at all and does not register the metric.

The startup banner shows what is in force:

```
Limits:    60 requests/min per client, 10/min to POST /api/execute
```

## Behind a reverse proxy

Clients are told apart by IP address. Behind a proxy every request arrives from the proxy's address, so all clients would share one limit. List the proxies in `trustedProxies`:

```json
{ "observe": { "rateLimit": { "trustedProxies": ["10.0.0.5", "10.0.0.6"] } } }
```

For a request from a listed proxy, the client is the rightmost `X-Forwarded-For` entry that is not itself a listed proxy. Entries further left were written by the client and are ignored, and the header is ignored entirely on requests from anywhere else, so a client cannot claim a new address per request to escape its limit. Entries must be IP addresses; host names are rejected at startup and never looked up.

Several users behind one NAT or corporate proxy that you do not control share an address, and so share a limit. Raise `globalRpm` if that is your situation. The dashboard itself makes about 8 requests a minute in the background.

Limits are counted in each server's memory. With several `nexora observe` instances behind a load balancer, each enforces its own limits.
