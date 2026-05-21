# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

---

## [Unreleased]

### Added

#### Distribution
- `nexora-all` - new shaded bundle module that aggregates all library modules (`nexora-api`, `nexora-common`, `nexora-core`, `nexora-plugin-spi`, `nexora-registry`, `nexora-tracing`, `nexora-retry`, `nexora-event`, `nexora-executor`, `nexora-plugin-loader`, `nexora-planner`, `nexora-capabilities`, `nexora-saga`, `nexora-runtime`, `nexora-persistence`) into a single deployable artifact; external transitive dependencies are promoted via `promoteTransitiveDependencies`; individual modules continue to be published as separate Maven packages to GitHub Packages alongside the bundle

#### Samples
- `screen_sanctions` step added to the payment pipeline 8-step DAG between `check_velocity` and `run_fraud_check`; fails with `SANCTIONS_HIT` when `forceBlockedUser=true`
- `screen_sanctions_compensate` capability added to the saga rollback chain
- Scenario 4 of the payment pipeline demo updated from a validation failure to a sanctions blocklist hit (`forceBlockedUser=true`) demonstrating early failure with partial saga compensation across the full 8-step DAG

### Fixed

- **`CapabilityRequest` rejects null inputs** - optional context bindings (e.g. `forceFailure`, `forceVelocityFail`, `forceBlockedUser`) resolve to `null` when the caller omits them from the execution context; `Map.copyOf()` throws `NullPointerException` on null values, causing all steps that declared those bindings to fail with an internal error and leaving dependent steps permanently pending. Null-valued entries are now stripped from the resolved inputs map before the immutable copy is taken; capabilities should use `getOrDefault()` or `Boolean.TRUE.equals()` to test absent flags.
- **`DagStepScheduler` leaves dependent steps permanently pending on unexpected exceptions** - when a step's `thenApplyAsync` threw an unchecked exception the future completed exceptionally, propagating through `CompletableFuture.allOf` and blocking all downstream steps indefinitely. A `.handle()` stage now intercepts any unchecked exception, publishes a `StepFailedEvent` with code `INTERNAL_ERROR`, and returns a failure `StepResult` so the pending counter decrements correctly and the execution terminates.
- **`run_fraud_check` demo capability embedded a null-prone `forceFailure` flag in its `Map.of()` output** - removed the flag from the success output map; it was a control input, not a produced output, and placing it in `Map.of()` caused `NullPointerException` on every execution that did not pass `forceFailure=true`.

---

## [0.1.0] - 2026-05-21

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

#### Samples
- `tests/payment-pipeline` - standalone Maven sample with a realistic fraud-detection DAG: `validate_request`, `enrich_user_data`, `check_velocity` → `run_fraud_check` → `process_payment` → `send_confirmation` + `update_ledger`; demonstrates plan amendments, capability contracts, and fallback routing

#### CI/CD
- `ci.yml` - builds and runs all tests on every push and pull request; uploads Surefire reports on failure
- `publish.yml` - publishes all modules to GitHub Packages and creates a versioned GitHub Release on `v*` tag push; pre-release flag set automatically for tags containing `-`

[Unreleased]: https://github.com/iamvirul/nexora/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/iamvirul/nexora/releases/tag/v0.1.0
