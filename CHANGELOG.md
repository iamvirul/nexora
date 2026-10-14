# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

---

## [Unreleased]
### Added

#### Persistence
- **Schema migrations with Flyway** - `JdbcExecutionStore` applies versioned migrations from `db/nexora/migration` during construction, before any query, and records them in `nexora_schema_history`. The location and table are namespaced so an application's own Flyway on the same database never picks them up. `V1__initial_schema.sql` holds the previous DDL. A failed or edited migration makes construction throw `IllegalStateException` naming the script. Stores created by earlier releases are baselined at version 0 and adopted in place, keeping their data. Migrations run over separate connections when the store owns its pool; a caller-supplied `DataSource` must allow at least two connections. Naming and authoring rules are in `CONTRIBUTING.md` ([#29](https://github.com/iamvirul/nexora/issues/29))
- **Connection pooling for `JdbcExecutionStore`** - The store now borrows a connection per call from a `DataSource` instead of serialising every call on one `synchronized` connection, so persistence calls run in parallel up to the pool size. New `JdbcExecutionStore(DataSource)` constructor for caller-managed pools (left open on `close()`), `JdbcExecutionStore(String, PoolConfig)` and `h2AutoServer(String, PoolConfig)` for a store-owned HikariCP pool, and `HikariCPDataSourceFactory`. The CLI reads `persistence.pool.maximumPoolSize`, `minimumIdle`, `connectionTimeoutMs` and `idleTimeoutMs` from `nexora.json` ([#28](https://github.com/iamvirul/nexora/issues/28))

#### Security
- **Authentication for the observe server** - Every `/api/*` route and the live WebSocket stream require an API key, sent as `Authorization: Bearer <key>`. A missing or wrong key gets `401` with `{"error":{"code":"UNAUTHORIZED","message":"..."}}` and `WWW-Authenticate: Bearer`, before any other check, so unknown `/api` paths do not reveal what exists. `/metrics`, `/health/*`, the dashboard page, `/openapi.yaml` and `/docs` stay public. On first start `nexora observe` generates a key, prints it once and saves only its SHA-256 digest (owner-only, `~/.nexora/observe-api-key.sha256`, override with `--api-key-file`); `NEXORA_API_KEY` or `--api-key` (at least 32 characters) supplies one instead, with nothing printed or written. `--rotate-api-key` replaces it. Keys are compared as SHA-256 digests with `MessageDigest.isEqual`, so timing reveals nothing. The dashboard trades the key once for an `HttpOnly; SameSite=Strict` session cookie (`POST /api/session`, `DELETE /api/session` to log out), and cookie-authenticated state changes and WebSocket handshakes must also carry the dashboard's own `Origin`. `nexora cancel` and `nexora replay` send the key from `NEXORA_API_KEY` or `--api-key` and warn when it would cross the network over plain HTTP. The payment pipeline demo uses the same auth with a key printed per run (nothing saved), and keeps its `/api/mock-webhook` receiver open for signed webhook deliveries. The auth classes live in `nexora-api` (`com.nexora.api.auth`: `ApiAuth`, `ApiKeyStore`, `UserStore`, `PasswordHasher`, `SessionEndpoints`) so any embedded HTTP server can reuse them ([#30](https://github.com/iamvirul/nexora/issues/30))

- **Username and password login with user API keys** - `nexora user add/passwd/remove/list` manages dashboard accounts in an owner-only `~/.nexora/observe-users` (`--users-file`), hashed with PBKDF2-HMAC-SHA256 at 600,000 iterations and a per-user salt; passwords are 12 to 128 characters. A correct password mints a user API key (`nxu_...`, 12 hours) that works everywhere the server's key does and is logged under the user's name. `POST /api/keys` returns it in the body for `nexora login`, which saves it to `~/.nexora/credentials` for `nexora cancel` / `nexora replay`; `POST /api/session` gives the dashboard the same key as the HttpOnly cookie instead. `GET /api/session` reports who is signed in, and `DELETE /api/session` (`nexora logout`) revokes it. Removing a user or changing a password revokes their keys on the running server, with no restart. Five failures lock a username for 15 minutes and each client is limited to 20 attempts a minute, checked before any hashing; unknown usernames get the same treatment and timing, so nothing reveals whether an account exists. Login must be `application/json` and same-origin. The dashboard signs in with a username and password by default, with the API key as an option; the payment pipeline demo stays key-only ([#30](https://github.com/iamvirul/nexora/issues/30))

- **Login demo** - New `tests/login-demo` sample runs the real observe server on `localhost:9470` with demo users `alice` and `bob`, a server key for the run, and a printed tour of dashboard sign-in, the lockout, `nexora login` / `cancel` / `logout`, user API keys with curl, and live revocation; sign-ins and changes show on its console as `audit` lines. Everything lives in a temp folder deleted on exit. The observe server is now a reusable `ObserveServer` (HTTP routes, dashboard, WebSocket and auth started and stopped together), which `nexora observe` and the demo both run ([#30](https://github.com/iamvirul/nexora/issues/30))

#### Observability
- **`migrations` readiness check** - `GET /health/ready`, `GET /health` and the `nexora_ready` gauge include a `migrations` check, `DOWN` when a schema migration is recorded as failed or the database schema is older than the one the store migrated to. New `ExecutionStore.isSchemaCurrent()` default method, `true` unless overridden ([#29](https://github.com/iamvirul/nexora/issues/29))

### Changed
- **Observe server API now requires a key** - Scripts calling `/api/*` must send `Authorization: Bearer <key>`, and `nexora cancel` / `nexora replay` need `NEXORA_API_KEY` or `--api-key`. `ObserveCommand.registerRoutes` and `ExecutionEndpoints.register` take an `ApiAuth`, and the payment pipeline demo needs its printed key or `NEXORA_API_KEY` ([#30](https://github.com/iamvirul/nexora/issues/30))
- **Portable upsert SQL** - `JdbcExecutionStore` replaces H2's `MERGE ... KEY` with standard `MERGE ... USING`, which runs on H2 and PostgreSQL 15+. Older PostgreSQL servers are rejected at construction, before anything is migrated, since every upsert would fail while health checks passed. `upsertStep` is now a single statement that keeps a recorded idempotency key when the update carries none. Both upserts retry once on a duplicate key, since concurrent `MERGE`s on a new row can both take the insert branch ([#29](https://github.com/iamvirul/nexora/issues/29))
- **Readiness reports four checks** - `checks` in `/health/ready` and `/health` now has `migrations` between `persistence` and `plugins`; clients that expect exactly three keys need updating ([#29](https://github.com/iamvirul/nexora/issues/29))
- **`JdbcExecutionStore(String jdbcUrl)` is backed by a single-connection HikariCP pool** - Same one-at-a-time behaviour as before for embedded H2 and tests. The CLI store now uses a pool of 10 by default; set `persistence.pool.maximumPoolSize` to change it. A named in-memory H2 URL without `DB_CLOSE_DELAY` gets `DB_CLOSE_DELAY=-1` so the database survives connection turnover, and an unnamed one (`jdbc:h2:mem:`) is rejected because every pooled connection would see its own empty database ([#28](https://github.com/iamvirul/nexora/issues/28))

---

## [0.3.0] - 2026-10-11
### Added

#### Core Engine
- **Execution cancellation** - `NexoraEngine.cancel(executionId)` returns `CompletableFuture<Boolean>` (`true` if cancelled, `false` if already finished; fails with `ExecutionNotFoundException` for unknown ids). Pending steps are never started, running steps are interrupted, the execution is persisted as `CANCELLED`, and `PlanCancelledEvent` fires. Saga compensation runs for completed steps when enabled. Cancelled executions are not dead-lettered. New `ExecutionStatus.CANCELLED` and `ExecutionState.CANCELLED` ([#33](https://github.com/iamvirul/nexora/issues/33))

#### Observability
- **`NexoraEngine.submit(intent)`** - Starts an execution and returns an `ExecutionHandle` (`executionId` + result future) immediately, so the id is known before the run finishes ([#33](https://github.com/iamvirul/nexora/issues/33))
- **OpenAPI specification for the observe server** - A hand-maintained OpenAPI 3.1 document covering every endpoint, with schemas and success and error examples, served at `GET /openapi.yaml`. Swagger UI is served at `GET /docs` from the bundled `org.webjars:swagger-ui` assets under a strict Content-Security-Policy, with no CDN or validator calls. CI lints the document with Redocly CLI, and `OpenApiSpecTest` fails the build when a route is added without documenting it or the document describes a route the server does not serve ([#46](https://github.com/iamvirul/nexora/issues/46))
- **Execution replay** - `NexoraEngine.replay(executionId)` re-runs a `FAILED`, `TIMED_OUT`, `CANCELLED` or `COMPENSATED` execution with the same goal and context under a new execution id; the original id is in the replay's context as `_replayOf`. Steps that completed reuse their original idempotency key so idempotent capabilities skip repeated side effects; failed steps, and every step of a compensated execution, get fresh keys. Exposed as `POST /api/executions/{id}/replay` (`202`, `404`, `409` with the current state), `nexora replay <executionId>`, and a Replay button in the history panel. Dead letter replay now delegates to it ([#37](https://github.com/iamvirul/nexora/issues/37))
- **Execution history REST API** - `GET /api/executions` lists executions newest first with keyset (cursor) pagination and `status`, `goal`, `from` / `to`, `limit` filters, responding `{"data": [...], "cursor": "...", "total": N}`. `GET /api/executions/{id}` returns one execution with its step records. `ExecutionStore` gains `findByFilter(ExecutionFilter)` and `count(ExecutionFilter)`; the JDBC store builds both with bound parameters only and adds an index on `(started_at, execution_id)`. The observe UI and the payment pipeline demo get an Execution History panel with status and goal filters, cursor-based Load more, and click-to-expand step detail ([#34](https://github.com/iamvirul/nexora/issues/34))
- **Cancel REST API, CLI, and UI** - `POST /api/execute` and DLQ replay now return the new `executionId`. The observe UI has a Cancel button on running executions, and the payment pipeline demo adds a cancellation scenario and a "Slow payment" preset. `DELETE /api/executions/{id}` on the observe server (`200` cancelled, `404` unknown, `409` already finished) and `nexora cancel <executionId> [--server URL]`. New `nexora_plan_cancelled_total` counter; cancels are not counted as failures ([#33](https://github.com/iamvirul/nexora/issues/33))
- **Health check endpoints** - `GET /health/live` (liveness), `GET /health/ready` (readiness: persistence, plugins, executor; `503` with a per-check breakdown when any is DOWN), and `GET /health` (summary with version, checks, and capability circuit states). All three are unauthenticated. New `nexora_ready` gauge and `NexoraNotReady` alert ([#31](https://github.com/iamvirul/nexora/issues/31))

### Changed
- **Code formatting enforced** - All Java code is formatted with palantir-java-format via the Spotless Maven plugin. `mvn verify` fails on unformatted code; run `mvn spotless:apply` to fix
- **`/health/ready` semantics** - Readiness now reflects the engine's own dependencies instead of capability circuit state. An OPEN circuit no longer returns `503`; circuit states moved to the `capabilities` field of `GET /health` ([#31](https://github.com/iamvirul/nexora/issues/31))
- **`PluginManager.loadPlugin(Path)` returns the plugin id** - Previously `void`. Source compatible; code compiled against an older `nexora-plugin-loader` must be recompiled ([#140](https://github.com/iamvirul/nexora/issues/140))
- **`DagStepScheduler.schedule(Plan, ExecutionContext, AtomicBoolean)` replaced by `schedule(Plan, ExecutionContext, HaltSignal)`** - `HaltSignal` carries why the execution stopped (`DEADLINE` or `CANCELLED`) so the scheduler can report `TIMED_OUT` or `CANCELLED`. Deadline behaviour is unchanged ([#33](https://github.com/iamvirul/nexora/issues/33))
- **Default webhook events include `CANCELLED`** - Intents without explicit `webhookEvents` are now notified when cancelled ([#33](https://github.com/iamvirul/nexora/issues/33))

### Fixed
- **Step records could be persisted out of order** - Step persistence ran as asynchronous event-bus handlers with no ordering, so a step's completion could be written before its start (leaving it `RUNNING`), and step rows could still be missing when the execution's result completed. Step persistence now uses the new `ExecutionEventBus.subscribeSync`, which runs inline in publish order; the step-start row, with its idempotency key, is written before the capability runs ([#37](https://github.com/iamvirul/nexora/issues/37))
- **Execution state write failures were silent** - `JdbcExecutionStore.updateExecution` swallowed SQL errors, so a failed terminal write could leave a record stuck at `COMPENSATING` (blocking replay) or at `FAILED` after compensation. It now throws, and the engine retries state writes up to three times. The engine also keeps an execution tracked until saga compensation has finished, so replay and cancel never act on a half-written record ([#37](https://github.com/iamvirul/nexora/issues/37))
- **Step idempotency keys were erased on completion** - `JdbcExecutionStore.upsertStep` replaced the whole row, and completion and failure updates carry no key, so the key recorded at step start was lost as soon as the step finished. Updates without a key now keep the recorded one ([#37](https://github.com/iamvirul/nexora/issues/37))
- **`findById` reported database errors as "not found"** - `JdbcExecutionStore.findById` swallowed query failures and returned empty, so a database outage surfaced as `404` from the detail endpoint and as `NOT_FOUND` from `cancel`. It now throws `IllegalStateException`; both endpoints answer `500` ([#34](https://github.com/iamvirul/nexora/issues/34))
- **Observe server failed to serialize `java.time` values** - `nexora-cli` lacked `jackson-datatype-jsr310`, so any response containing an `Instant` or `Duration` threw instead of responding: `GET /api/dead-letters` with entries, webhook delivery records, and `GET /health` once capabilities were registered. Instants serialize as ISO-8601 timestamps and durations as ISO-8601 durations (`PT0.25S`) ([#34](https://github.com/iamvirul/nexora/issues/34))
- **Timed-out capabilities kept running** - `TimeoutInterceptor` used `CompletableFuture.cancel(true)`, which does not interrupt the worker, so a capability that exceeded its timeout carried on in the background. It now runs on a `FutureTask` and is interrupted on timeout ([#33](https://github.com/iamvirul/nexora/issues/33))
- **Retries after interruption** - `RetryInterceptor` no longer starts another attempt once the step thread has been interrupted ([#33](https://github.com/iamvirul/nexora/issues/33))
- **Plugin jars never activated** - `NexoraEngine.Builder.withPluginJar()` loaded the jar but never activated the plugin, so its capabilities and planners were never registered and executions failed with `CAPABILITY_NOT_FOUND`. `build()` now activates jar plugins after inline plugins, in the order added, and fails with `PluginInitializationException` if activation fails ([#140](https://github.com/iamvirul/nexora/issues/140))

---

## [0.2.1] - 2026-07-03
- **Dependency Update** - Bumps ch.qos.logback:logback-classic from 1.5.35 to 1.5.37.

## [0.2.0] - 2026-06-30
### Added

#### Core Engine
- **Cron Scheduling** - Recurring intent executions driven by 5-field UNIX cron expressions. `NexoraEngine.schedule(cron, intent)` persists the schedule to a `nexora_schedules` table and returns a `ScheduledExecution` handle with `id()`, `nextFireTime()`, and `cancel()`. Three missed-fire policies control restart behaviour: `SKIP` (drop missed windows), `FIRE_ONCE` (fire once to catch up, default), and `FIRE_ALL` (fire once per missed window). `ScheduledExecutionFiredEvent` and `ScheduledExecutionMissedEvent` are published on the event bus. Requires a persistence store ([#38](https://github.com/iamvirul/nexora/issues/38))
- **Dead Letter Queue** - Permanently failed executions (after all retries are exhausted) are now captured in a `nexora_dead_letters` table. Each dead letter record stores `executionId`, `goal`, `context`, `failureCode`, `failureMessage`, `failedAt`, and a `reviewState` (`PENDING` / `RESOLVED` / `REPLAYED`). An `ExecutionDeadLetteredEvent` fires on the event bus for external alerting ([#42](https://github.com/iamvirul/nexora/issues/42))
- **Conditional Branching** - Allows defining a `StepCondition` (e.g. `ContextValueEquals`, `StepOutputEquals`) on any step. The scheduler dynamically evaluates these before step execution and skips the step if the condition resolves to `false` ([#41](https://github.com/iamvirul/nexora/issues/41))
- **Plan-level execution deadline / timeout** - wall-clock deadline that cancels an entire plan execution when the limit is exceeded, returning `TIMED_OUT` execution status and triggering full saga compensation; overridable per `Intent` or globally via `NexoraEngine.Builder.withDefaultPlanDeadline()` ([#35](https://github.com/iamvirul/nexora/issues/35))

#### Executor
- **Capability contracts enhancements** - Added `openDuration` and `probeInterval` properties to `CapabilityContract` to support stateful circuit breaking ([#36](https://github.com/iamvirul/nexora/issues/36))
- **Capability circuit breaker** - `CapabilityContractMonitor` now implements a robust circuit breaker state machine (CLOSED, OPEN, HALF_OPEN) and routes unhealthy capabilities to their fallback transparently ([#36](https://github.com/iamvirul/nexora/issues/36))

#### Observability
- **Schedule REST API** - Three new endpoints on the observability server: `GET /api/schedules` (list all schedules), `POST /api/schedules` (register a schedule with `cronExpression`, `goal`, and optional `missedFirePolicy`), `DELETE /api/schedules/{id}` (cancel immediately). The observe dashboard includes a live Cron Schedules panel with an add-form and auto-refresh every 30 seconds ([#38](https://github.com/iamvirul/nexora/issues/38))
- **DLQ REST API** - Three new endpoints on the observability server: `GET /api/dead-letters` (paginated, filterable by `state`; defaults to `PENDING`), `POST /api/dead-letters/{id}/replay` (creates a new execution and marks the entry as `REPLAYED`), `POST /api/dead-letters/{id}/resolve` (accepts an optional `reason` body and marks the entry as `RESOLVED`). Authentication will be enforced once [#30](https://github.com/iamvirul/nexora/issues/30) lands ([#42](https://github.com/iamvirul/nexora/issues/42))
- **Circuit breaker monitoring** - Added `/health/ready` endpoint to the observability server to monitor capabilities circuit statuses ([#36](https://github.com/iamvirul/nexora/issues/36))
- **Webhook Callbacks** - Implemented asynchronous terminal event webhooks using Java 11 `HttpClient` with exponential backoff. Webhooks are signed with an HMAC-SHA256 signature and delivery attempts are persisted in `nexora_webhook_deliveries` and exposed via `/api/webhook-deliveries/{executionId}` ([#39](https://github.com/iamvirul/nexora/issues/39))

#### CLI
- **`nexora schedule`** - New subcommand group for cron schedule management: `nexora schedule add --goal "<goal>" --cron "<expr>" [--policy SKIP|FIRE_ONCE|FIRE_ALL]`, `nexora schedule list [--active-only]`, `nexora schedule remove <id>`. Requires `executionStore` set in `nexora.json` ([#38](https://github.com/iamvirul/nexora/issues/38))
- **`nexora dlq`** - New subcommand group for dead letter queue management: `nexora dlq list [--state PENDING|RESOLVED|REPLAYED|ALL]`, `nexora dlq replay <id>`, `nexora dlq resolve <id> [--reason "..."]` ([#42](https://github.com/iamvirul/nexora/issues/42))

### Fixed

- **Observability UI gets stuck on RUNNING for timed-out plans** - `NexoraObservability` tracker missed the `PlanTimedOutEvent`, leaving the UI infinitely spinning on `RUNNING`. Fixed by adding the event listener and updating the dashboard UI to correctly render the orange `TIMED_OUT` badge.

---

## [0.1.0] - 2026-05-22

### Added

#### Core Engine
- Initial implementation of the **Intent-Based Execution Engine (IBEE)**
- `nexora-core` - `Intent`, `Plan`, `Step`, `InputBinding`, `CapabilityRequest`, `CapabilityResult`, `ExecutionContext`, `TraceContext`
- `nexora-plugin-spi` - `NexoraPlugin`, `CapabilityProvider`, `Capability`, `CapabilityDescriptor`, `CapabilityContract`, `Planner` interfaces
- `nexora-planner` - `RulePlanner` matching step definitions to goals via predicate matchers; `PlanRegistry` for step definition storage
- `nexora-event` - sealed `ExecutionEvent` hierarchy; `InProcessEventBus` with typed subscriptions
- `nexora-registry` - `DefaultCapabilityRegistry`
- `nexora-retry` - `RetryPolicy`, `RetryPolicyRegistry`, `ExponentialBackoffPolicy`
- `nexora-tracing` - `Tracer` SPI, `NoopTracer`
- `nexora-runtime` - `ExecutionEngine` orchestrating planner + scheduler + event bus
- `nexora-api` - `NexoraEngine` public facade with fluent builder

#### Executor
- `DagStepScheduler` executes plan steps in parallel respecting declared `dependsOn` relationships; cycle detection via DFS before execution starts
- `InterceptorPipeline` and `ExecutionInterceptor` SPI for wrapping capability invocations
- `TracingInterceptor` propagates `TraceContext` through all capability calls
- `CapabilityInvoker` as the terminal pipeline node; resolves capabilities from the registry
- `RetryInterceptor` and `TimeoutInterceptor` wired into the default interceptor pipeline

#### Plugins
- Plugin loader - loads `NexoraPlugin` implementations from external JARs via `URLClassLoader`; manages plugin lifecycle (register → activate → deactivate → unload)
- **Pluggable planner SPI** - plugins can supply custom `Planner` implementations; `CompositePlanner` tries plugin planners first and falls back to the rule-based planner
- **Reactive plan amendments** - `CapabilityResult` can return `PlanAmendment` entries; `DagStepScheduler` applies `AddStepAmendment`, `SkipStepAmendment`, and `ModifyInputAmendment` atomically before dependent steps start
- **Capability contracts** - `CapabilityContract` declares p99 latency SLA, max error rate, and fallback capability ID; `CapabilityContractMonitor` maintains a sliding window and routes unhealthy capabilities to their fallback transparently

#### CLI
- `nexora` CLI - `run`, `demo`, `observe`, and `help` sub-commands via picocli
- `EngineFactory` - builds `NexoraEngine` from a `CliConfig` YAML file

#### Observability
- **Observability server** (`nexora observe`) - embedded HTTP server serving a live process dashboard, Prometheus `/metrics` endpoint, and `/api/process` snapshot endpoint
- `NexoraObservability` - attaches to any `NexoraEngine` via event subscriptions; tracks execution lifecycle, step latencies, error rates, and amendment counts using Prometheus counters/gauges/histograms
- `ProcessSnapshot` and `ExecutionSnapshot` DTOs for the process view API
- **WebSocket push** - `SnapshotListener` SPI broadcasts live `ProcessSnapshot` JSON to connected browser clients over a dedicated port; `ObserveCommand` injects the port into the bundled HTML at serve time
- Live observe UI - professional dark theme, Gantt-style timeline showing step parallelism, viewport-height layout with the execution list as the sole scrollable area, mobile responsive, live "Updated" counter
- Grafana stack integration (docker-compose) with a pre-built dashboard

#### Production Readiness
- **Idempotency keys** - `CapabilityRequest` carries a UUID per step-execution attempt; propagated through `StepStartedEvent` so capabilities can deduplicate side effects on retry
- **Durable execution state** - `ExecutionStore` SPI with a JDBC/H2 default; persists execution lifecycle and per-step start/complete/fail transitions; opt in via `NexoraEngine.builder().withExecutionStore(...)`
- **Contract enforcement** - capability p99 SLA used as the effective `TimeoutInterceptor` deadline when no explicit step timeout is set
- **Orchestration-based saga** - `SagaOrchestrator` runs compensating capabilities in reverse topological order on partial execution failure; `Step.compensateCapabilityId` declares the rollback capability; opt in via `.withSagaEnabled(true)`
- `CompensationStartedEvent`, `CompensationCompletedEvent`, `CompensationFailedEvent` added to the sealed `ExecutionEvent` hierarchy

#### Distribution
- `nexora-all` - shaded bundle module aggregating all library modules (`nexora-api`, `nexora-common`, `nexora-core`, `nexora-plugin-spi`, `nexora-registry`, `nexora-tracing`, `nexora-retry`, `nexora-event`, `nexora-executor`, `nexora-plugin-loader`, `nexora-planner`, `nexora-capabilities`, `nexora-saga`, `nexora-runtime`, `nexora-persistence`) into a single deployable artifact; external transitive dependencies are promoted; individual modules are published separately alongside the bundle

#### Samples
- `tests/payment-pipeline` - standalone 8-step fraud-detection DAG (`validate_request`, `enrich_user_data`, `check_velocity`, `screen_sanctions` → `run_fraud_check` → `process_payment` → `send_confirmation` + `update_ledger`) demonstrating plan amendments, capability contracts, fallback routing, and orchestration-based saga compensation
- `screen_sanctions` step added between `check_velocity` and `run_fraud_check`; fails with `SANCTIONS_HIT` when `forceBlockedUser=true`; `screen_sanctions_compensate` included in the saga rollback chain
- Four startup scenarios: happy path, high-risk fraud review, gateway failure with saga, and sanctions blocklist hit with partial compensation

#### CI/CD
- `ci.yml` - builds and runs all tests on every push and pull request; uploads Surefire reports on failure
- `publish.yml` - publishes each Maven module individually to GitHub Packages in dependency order, then creates a versioned GitHub Release on `v*` tag push; pre-release flag set automatically for tags containing `-`
- `bump-version.yml` - triggered automatically after a release is published; computes the next SNAPSHOT version, updates all `pom.xml` files via `mvn versions:set`, and opens a pull request to merge the version bump back to the default branch

### Fixed

- **`CapabilityRequest` rejects null inputs** - optional context bindings (e.g. `forceFailure`, `forceVelocityFail`, `forceBlockedUser`) resolve to `null` when the caller omits them; `Map.copyOf()` throws `NullPointerException` on null values, causing steps to fail with an internal error and leaving dependent steps permanently pending. Null-valued entries are now stripped before the immutable copy is taken; capabilities should use `getOrDefault()` or `Boolean.TRUE.equals()` to test absent flags.
- **`DagStepScheduler` leaves dependent steps permanently pending on unexpected exceptions** - when a step's `thenApplyAsync` threw an unchecked exception the future completed exceptionally, propagating through `CompletableFuture.allOf` and blocking all downstream steps indefinitely. A `.handle()` stage now intercepts any exception, publishes a `StepFailedEvent` with code `INTERNAL_ERROR`, and returns a failure `StepResult` so the pending counter decrements correctly and the execution terminates.
- **`run_fraud_check` embedded a null-prone `forceFailure` flag in its `Map.of()` output** - removed; it was a control input, not a produced output.

---

[Unreleased]: https://github.com/iamvirul/nexora/compare/v0.3.0...HEAD
[0.1.0]: https://github.com/iamvirul/nexora/releases/tag/v0.1.0
[0.2.0]: https://github.com/iamvirul/nexora/releases/tag/v0.2.0
[0.2.1]: https://github.com/iamvirul/nexora/releases/tag/0.2.1
[0.3.0]: https://github.com/iamvirul/nexora/releases/tag/v0.3.0
