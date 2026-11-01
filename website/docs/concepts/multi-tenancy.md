---
id: multi-tenancy
title: Multi-tenancy
---

# Multi-tenancy

One Nexora deployment can serve several teams or customers, each seeing only its own executions, history, dead letters and schedules. Every execution belongs to a **tenant**, named by a tenant id such as `acme`.

Single-tenant is the default and behaves exactly as Nexora did before tenants existed: everything belongs to the tenant `default` and nothing is filtered.

## Tenant ids

1 to 64 characters of `a-z`, `0-9`, `-` and `_`, starting with a letter or digit. Ids are lowercase so `Acme` and `acme` can never become two tenants. Anything else is rejected where it enters: the builder, an `Intent`, the `X-Tenant-ID` header, or `nexora user`.

## Library: tenants on intents

```java
Intent intent = new Intent("process order", Map.of("orderId", "123"))
        .withTenantId("acme");
engine.execute(intent);
```

An intent without a tenant runs for `default`. The tenant is stored with the execution, its steps, webhook deliveries, its dead letter if it fails, and any schedule created from it, so every firing of that schedule runs for the same tenant. A replay runs for the tenant of the execution it replays.

Capabilities read the tenant from their request, for per-tenant credentials or state:

```java
public CapabilityResult execute(CapabilityRequest request) {
    String tenant = request.tenantId(); // never null; "default" for single-tenant work
    ...
}
```

Planners and the engine see the same value as `ExecutionContext.tenantId()`.

Tenant-scoped lookups treat another tenant's execution exactly like an unknown id:

| Call | Another tenant's id |
|---|---|
| `engine.replay(tenantId, executionId)` | `ExecutionNotFoundException` |
| `engine.cancel(tenantId, executionId)` | fails with `ExecutionNotFoundException` |
| `engine.cancelSchedule(tenantId, scheduleId)` | returns `false`, changes nothing |
| `engine.listSchedules(tenantId)` | not listed |
| `store.findById(tenantId, executionId)` | `Optional.empty()` |
| `store.findByFilter(filter.forTenant(tenantId))` | not returned, not counted |
| `store.findDeadLetters(tenantId, state, offset, limit)` | not returned |

### One engine per tenant

```java
NexoraEngine acme = NexoraEngine.builder()
        .withExecutionStore(store)
        .withTenantId("acme")
        .build();
```

A tenant-bound engine runs intents without a tenant for `acme`, refuses an intent for any other tenant with `IllegalArgumentException`, and its replay, cancel and schedule calls only reach `acme`'s work. Its cron scheduler reloads and fires only `acme`'s schedules.

Engines sharing one store should be all bound or all unbound. An unbound engine serves every tenant, so it reloads and fires every tenant's schedules, including those a bound engine already runs.

### Capabilities for some tenants only

A capability registered for every tenant (the default) can be restricted:

```java
new CapabilityDescriptor("acme-billing", "Bills through Acme's account", inputs, outputs, true, false)
        .withTenantIds(Set.of("acme"));
```

Other tenants neither plan with it (`CapabilityRegistry.listFor(tenantId)`) nor can invoke it: a step that names it fails with `CAPABILITY_NOT_FOUND`, the same answer as for an id that does not exist, so tenants cannot discover each other's capabilities. `engine.listCapabilities(tenantId)` lists what a tenant may use.

## Observe server: `--multi-tenant`

```bash
nexora user add alice --tenant acme
nexora user add bob --tenant globex
nexora observe --multi-tenant
```

Every `/api` route for executions, history, dead letters, schedules, webhook deliveries and the process snapshot, and the live WebSocket stream, then acts for exactly one tenant. Which one comes from the credential wherever the credential has one. The `X-Tenant-ID` header can confirm that tenant but never change it, since trusting a client-chosen header alone would let any user read any tenant:

| Credential | Tenant | `X-Tenant-ID` |
|---|---|---|
| User key (`nexora login`, `POST /api/keys`) or user session | The user's tenant | Optional. Another tenant: `403 TENANT_FORBIDDEN` |
| Server API key | Named per request | Required. Missing: `400 TENANT_REQUIRED` |
| Dashboard session opened with the server API key | The tenant named when signing in | Optional. Another tenant: `403 TENANT_FORBIDDEN` |

An invalid header is `400 INVALID_TENANT_ID`. Errors use the usual schema:

```json
{"error": {"code": "TENANT_REQUIRED", "message": "This server is multi-tenant: send X-Tenant-ID to name the tenant"}}
```

Another tenant's execution, dead letter or schedule answers `404`, exactly like one that does not exist. New executions and schedules are created for the request's tenant.

```bash
# Server key: name the tenant on every request
curl -H "Authorization: Bearer $NEXORA_API_KEY" -H "X-Tenant-ID: acme" \
     https://nexora.example.com/api/executions

# CLI with the server key
nexora cancel <execution-id> --tenant acme --server https://nexora.example.com

# CLI after `nexora login` as alice: her key already carries acme
nexora replay <execution-id> --server https://nexora.example.com
```

In the dashboard, users sign in as usual and see only their tenant. Signing in with the server API key asks for the tenant to open; that session stays on it.

Moving a user to another tenant ends their sessions and keys, like a password change:

```bash
nexora user tenant alice globex
```

Without `--multi-tenant` the server is single-tenant: every caller sees every execution and `X-Tenant-ID` is ignored.

Execution metrics at `/metrics` carry a `tenant` label (see [Observability](../observability.md)); `/metrics` itself stays public and unscoped, like every scrape endpoint.

## Database

Migration `V3__add_tenant_id.sql` adds a `tenant_id` column (`VARCHAR(64) NOT NULL DEFAULT 'default'`) to `nexora_executions`, `nexora_steps`, `nexora_webhook_deliveries`, `nexora_dead_letters` and `nexora_schedules`. Existing rows join the `default` tenant and keep all their data; PostgreSQL 11 and later add the column without rewriting the table. Steps and webhook deliveries copy the tenant of their execution when written, so every table can be filtered on its own.

### Locking down direct database access (PostgreSQL)

Nexora filters by tenant in every query it runs. People and tools that query the database directly, such as reporting users, are not covered by that. PostgreSQL row-level security can hold them to their own tenant.

Nexora's own database user owns the tables (it runs the migrations), and table owners bypass row-level security, so Nexora itself is unaffected. Do not use `FORCE ROW LEVEL SECURITY`.

Map database roles to tenants in a table only an administrator can write, so a reader cannot pick another tenant for itself (a session variable could simply be `SET` to any value):

```sql
CREATE TABLE nexora_tenant_roles (
    role_name  NAME        PRIMARY KEY,
    tenant_id  VARCHAR(64) NOT NULL
);
REVOKE ALL ON nexora_tenant_roles FROM PUBLIC;

DO $$
DECLARE t TEXT;
BEGIN
    FOREACH t IN ARRAY ARRAY['nexora_executions', 'nexora_steps', 'nexora_webhook_deliveries',
                             'nexora_dead_letters', 'nexora_schedules'] LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format(
            'CREATE POLICY tenant_isolation ON %I FOR SELECT USING (tenant_id IN '
            '(SELECT tenant_id FROM nexora_tenant_roles WHERE role_name = current_user))', t);
    END LOOP;
END $$;
```

Then give each reader a role, its tenant, and read access:

```sql
CREATE ROLE acme_reporting LOGIN PASSWORD '...';
INSERT INTO nexora_tenant_roles VALUES ('acme_reporting', 'acme');
GRANT SELECT ON nexora_executions, nexora_steps, nexora_webhook_deliveries,
                nexora_dead_letters, nexora_schedules TO acme_reporting;
GRANT SELECT ON nexora_tenant_roles TO acme_reporting;
```

`acme_reporting` now sees only `acme`'s rows. A role with no entry in `nexora_tenant_roles` sees nothing. The policies only allow reads; grant no `INSERT`, `UPDATE` or `DELETE` to these roles.
