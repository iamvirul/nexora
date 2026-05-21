# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

---

## [Unreleased]

---

## [1.0.0] - 2026-05-21

### Added

#### Production Readiness
- **Idempotency keys** — `CapabilityRequest` now carries a UUID per step-execution attempt; propagated through `StepStartedEvent` so capabilities can deduplicate side effects on retry
- **Durable execution state** — `ExecutionStore` SPI with a JDBC/H2 default implementation; persists execution lifecycle and per-step start/complete/fail transitions; opt in via `NexoraEngine.builder().withExecutionStore(...)`
- **Contract enforcement** — capability p99 SLA is now used as the effective `TimeoutInterceptor` deadline when no explicit step timeout is set; was previously declarative only
- **Orchestration-based saga** — `SagaOrchestrator` runs compensating capabilities in reverse topological order on partial execution failure; `Step.compensateCapabilityId` declares the rollback capability; opt in via `.withSagaEnabled(true)`
- `CompensationStartedEvent`, `CompensationCompletedEvent`, `CompensationFailedEvent` added to the sealed `ExecutionEvent` hierarchy
- `ExecutionState` enum: `RUNNING`, `COMPLETED`, `FAILED`, `COMPENSATING`, `COMPENSATED`
- `StepState` enum: `PENDING`, `RUNNING`, `COMPLETED`, `FAILED`, `SKIPPED`, `COMPENSATING`, `COMPENSATED`, `COMPENSATION_FAILED`

#### Observability
- **WebSocket push** — `NexoraObservability` now exposes a `SnapshotListener` SPI; the observe server broadcasts live `ProcessSnapshot` JSON to all connected browser clients over a dedicated WebSocket port (HTTP port + 1)
- `ObserveCommand` wires a `SnapshotBroadcaster` (Java-WebSocket) and replaces the `{{WS_PORT}}` placeholder in the bundled HTML at serve time

#### CLI & Demo
- **Observe UI redesign** — replaced the original animated UI with a professional dark theme; Gantt-style timeline showing step parallelism; viewport-height layout with the execution list as the sole scrollable area; footer pinned at bottom; mobile responsive; live "Updated" counter ticking every second
- `DemoCapabilities` extracted from `DemoCommand` into a reusable package-private helper; `DemoCommand` is now a thin shell

#### Samples
- **`tests/payment-pipeline`** — standalone Maven sample demonstrating a realistic fraud-detection DAG: `validate_request`, `enrich_user_data`, `check_velocity` → `run_fraud_check` → `process_payment` → `send_confirmation` + `update_ledger`; `run_fraud_check` injects `flag_for_review` as a plan amendment; `process_payment` has a p99=300ms SLA with a declared fallback

#### CI/CD
- `ci.yml` — builds and runs all tests on every push and pull request; uploads Surefire reports on failure
- `publish.yml` — publishes all modules to GitHub Packages and creates a versioned GitHub Release on `v*` tag push; pre-release flag set automatically for tags containing `-`
- `distributionManagement` added to root pom pointing at `https://maven.pkg.github.com/iamvirul/nexora`

### Changed
- `StepStartedEvent` now includes `idempotencyKey` field
- `StepDefinition` gains an optional `compensateCapabilityId` constructor parameter (backward-compatible; defaults to `null`)
- `Step` record gains `compensateCapabilityId` as the 8th field (backward-compatible; `Step.of(...)` factory unchanged)
- `DagStepScheduler` constructor now requires `CapabilityRegistry` to resolve contract timeouts
- `NexoraEngine.Builder` gains `withExecutionStore(ExecutionStore)` and `withSagaEnabled(boolean)`

---

## [0.5.0] - 2026-05-20

### Added
- **Observability server** (`nexora-cli observe`) — embedded HTTP server serving a live process dashboard, Prometheus `/metrics` endpoint, and `/api/process` snapshot endpoint
- `NexoraObservability` — attaches to any `NexoraEngine` via event subscriptions; tracks execution lifecycle, step latencies, error rates, and amendment counts using Prometheus counters/gauges/histograms
- `ProcessSnapshot` and `ExecutionSnapshot` DTOs for the process view API
- Grafana stack integration (docker-compose) with a pre-built dashboard

---

## [0.4.0] - 2026-05-19

### Added
- **Pluggable planner SPI** — plugins can now supply custom `Planner` implementations via `NexoraPlugin`; `CompositePlanner` tries plugin planners first and falls back to the rule-based planner
- **Reactive plan amendments** — `CapabilityResult` can return `PlanAmendment` entries; `DagStepScheduler` applies `AddStepAmendment`, `SkipStepAmendment`, and `ModifyInputAmendment` atomically before dependent steps start
- **Capability contracts** — `CapabilityContract` declares p99 latency SLA, max error rate, and fallback capability ID; `CapabilityContractMonitor` maintains a sliding window and routes unhealthy capabilities to their fallback transparently
- `SkipStepAmendment` and `ModifyInputAmendment` added alongside the existing `AddStepAmendment`

### Fixed
- Plugin lifecycle event ordering under concurrent activation/deactivation
- Contract monitor logging format for error rate values

---

## [0.3.0] - 2026-05-18

### Added
- **CLI** (`nexora`) — `run`, `demo`, `observe`, and `help` sub-commands via picocli
- `EngineFactory` — builds `NexoraEngine` from a `CliConfig` YAML file
- `RetryInterceptor` and `TimeoutInterceptor` wired into the default interceptor pipeline
- `ExponentialBackoffPolicy` with configurable initial delay, multiplier, and max delay

---

## [0.2.0] - 2026-05-17

### Added
- **Executor module** — `DagStepScheduler` executes plan steps in parallel respecting declared `dependsOn` relationships; cycle detection via DFS before execution starts
- `InterceptorPipeline` and `ExecutionInterceptor` SPI for wrapping capability invocations
- `TracingInterceptor` propagates `TraceContext` through all capability calls
- `CapabilityInvoker` as the terminal pipeline node; resolves capabilities from the registry
- Plugin loader — loads `NexoraPlugin` implementations from external JARs via `URLClassLoader`; manages plugin lifecycle (register → activate → deactivate → unload)

---

## [0.1.0] - 2026-05-15

### Added
- Initial implementation of the **Intent-Based Execution Engine (IBEE)**
- `nexora-core` — `Intent`, `Plan`, `Step`, `InputBinding`, `CapabilityRequest`, `CapabilityResult`, `ExecutionContext`, `TraceContext`
- `nexora-plugin-spi` — `NexoraPlugin`, `CapabilityProvider`, `Capability`, `CapabilityDescriptor`, `CapabilityContract`, `Planner` interfaces
- `nexora-planner` — `RulePlanner` matching step definitions to goals via predicate matchers; `PlanRegistry` for step definition storage
- `nexora-event` — sealed `ExecutionEvent` hierarchy; `InProcessEventBus` with typed subscriptions
- `nexora-registry` — `DefaultCapabilityRegistry`
- `nexora-retry` — `RetryPolicy`, `RetryPolicyRegistry`, `ExponentialBackoffPolicy`
- `nexora-tracing` — `Tracer` SPI, `NoopTracer`
- `nexora-runtime` — `ExecutionEngine` orchestrating planner + scheduler + event bus
- `nexora-api` — `NexoraEngine` public facade with fluent builder

[Unreleased]: https://github.com/iamvirul/nexora/compare/v1.0.0...HEAD
[1.0.0]: https://github.com/iamvirul/nexora/compare/v0.5.0...v1.0.0
[0.5.0]: https://github.com/iamvirul/nexora/compare/v0.4.0...v0.5.0
[0.4.0]: https://github.com/iamvirul/nexora/compare/v0.3.0...v0.4.0
[0.3.0]: https://github.com/iamvirul/nexora/compare/v0.2.0...v0.3.0
[0.2.0]: https://github.com/iamvirul/nexora/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/iamvirul/nexora/releases/tag/v0.1.0
