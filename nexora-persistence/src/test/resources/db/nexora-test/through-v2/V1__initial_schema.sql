-- Initial Nexora schema: the DDL JdbcExecutionStore created before Flyway.
--
-- IF NOT EXISTS is deliberate. Stores created by earlier releases already hold some or all of
-- these tables but no schema history, and are baselined at version 0, so this script also runs
-- against them: it adds whatever tables an older release never created and leaves the rest alone.

CREATE TABLE IF NOT EXISTS nexora_executions (
    execution_id  VARCHAR(36)  PRIMARY KEY,
    trace_id      VARCHAR(64),
    goal          TEXT,
    context_json  TEXT,
    state         VARCHAR(30)  NOT NULL,
    started_at    TIMESTAMP    NOT NULL,
    completed_at  TIMESTAMP
);

-- Backs the history API keyset pagination (ORDER BY started_at DESC, execution_id DESC).
CREATE INDEX IF NOT EXISTS idx_nexora_executions_started ON nexora_executions (started_at, execution_id);

CREATE TABLE IF NOT EXISTS nexora_steps (
    execution_id     VARCHAR(36)  NOT NULL,
    step_id          VARCHAR(200) NOT NULL,
    capability_id    VARCHAR(200) NOT NULL,
    idempotency_key  VARCHAR(36),
    state            VARCHAR(30)  NOT NULL,
    failure_code     VARCHAR(100),
    failure_message  TEXT,
    started_at       TIMESTAMP,
    completed_at     TIMESTAMP,
    duration_ms      BIGINT,
    PRIMARY KEY (execution_id, step_id),
    FOREIGN KEY (execution_id) REFERENCES nexora_executions(execution_id)
);

CREATE TABLE IF NOT EXISTS nexora_webhook_deliveries (
    delivery_id      VARCHAR(36)  PRIMARY KEY,
    execution_id     VARCHAR(36)  NOT NULL,
    url              TEXT         NOT NULL,
    status_code      INT          NOT NULL,
    attempt          INT          NOT NULL,
    successful       BOOLEAN      NOT NULL,
    timestamp        TIMESTAMP    NOT NULL,
    error_message    TEXT,
    FOREIGN KEY (execution_id) REFERENCES nexora_executions(execution_id)
);

CREATE TABLE IF NOT EXISTS nexora_dead_letters (
    id               VARCHAR(36)  PRIMARY KEY,
    execution_id     VARCHAR(36)  NOT NULL,
    goal             TEXT,
    context_json     TEXT,
    failure_code     VARCHAR(100),
    failure_message  TEXT,
    failed_at        TIMESTAMP    NOT NULL,
    review_state     VARCHAR(30)  NOT NULL DEFAULT 'PENDING',
    resolve_reason   TEXT
);

CREATE TABLE IF NOT EXISTS nexora_schedules (
    id                   VARCHAR(36)   PRIMARY KEY,
    cron_expression      VARCHAR(120)  NOT NULL,
    goal                 TEXT          NOT NULL,
    context_json         TEXT,
    missed_fire_policy   VARCHAR(20)   NOT NULL DEFAULT 'FIRE_ONCE',
    created_at           TIMESTAMP     NOT NULL,
    last_fired_at        TIMESTAMP,
    next_fire_at         TIMESTAMP     NOT NULL,
    last_status          VARCHAR(20)   NOT NULL DEFAULT 'NEVER_RUN',
    active               BOOLEAN       NOT NULL DEFAULT TRUE
);
