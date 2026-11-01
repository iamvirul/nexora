-- Multi-tenancy (#48): every row belongs to a tenant.
--
-- Existing rows, and rows written by instances still on the previous release during a rolling
-- deploy, get the DEFAULT tenant: everything stored before tenants existed was single-tenant.
-- PostgreSQL 11+ adds a column with a constant default without rewriting the table.
--
-- Steps and webhook deliveries copy the tenant of their execution when written. The column is
-- repeated on them, rather than joined, so PostgreSQL row-level security policies can check every
-- table on its own (see website/docs/concepts/multi-tenancy.md).

ALTER TABLE nexora_executions ADD COLUMN tenant_id VARCHAR(64) DEFAULT 'default' NOT NULL;

ALTER TABLE nexora_steps ADD COLUMN tenant_id VARCHAR(64) DEFAULT 'default' NOT NULL;

ALTER TABLE nexora_webhook_deliveries ADD COLUMN tenant_id VARCHAR(64) DEFAULT 'default' NOT NULL;

ALTER TABLE nexora_dead_letters ADD COLUMN tenant_id VARCHAR(64) DEFAULT 'default' NOT NULL;

ALTER TABLE nexora_schedules ADD COLUMN tenant_id VARCHAR(64) DEFAULT 'default' NOT NULL;

-- History is always read within one tenant, newest first, with keyset pagination
-- (WHERE tenant_id = ? ORDER BY started_at DESC, execution_id DESC). The tenant-less index from V1
-- still serves single-tenant deployments, which query without a tenant.
CREATE INDEX idx_nexora_executions_tenant_started ON nexora_executions (tenant_id, started_at, execution_id);

-- The dead letter list pages within one tenant, newest failure first.
CREATE INDEX idx_nexora_dead_letters_tenant_failed ON nexora_dead_letters (tenant_id, failed_at);
