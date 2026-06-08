# Nexora

[![CI](https://github.com/iamvirul/nexora/actions/workflows/ci.yml/badge.svg)](https://github.com/iamvirul/nexora/actions/workflows/ci.yml)
[![Trivy Scan](https://github.com/iamvirul/nexora/actions/workflows/trivy.yml/badge.svg)](https://github.com/iamvirul/nexora/actions/workflows/trivy.yml)
[![Java](https://img.shields.io/badge/Java-21%2B-blue.svg)](https://www.oracle.com/java/)
[![License](https://img.shields.io/github/license/iamvirul/nexora)](https://github.com/iamvirul/nexora/blob/main/LICENSE)
[![GitHub last commit](https://img.shields.io/github/last-commit/iamvirul/nexora)](https://github.com/iamvirul/nexora/commits/main)

Nexora is a Java execution engine that turns a high-level goal into a set of steps and runs them. You tell it what you want to happen, and it figures out the order, runs independent steps in parallel, and gives you back a result.

<img width="1346" height="1162" alt="image" src="https://github.com/user-attachments/assets/96b64472-26a5-42cf-93dd-990062c9ba23" />


The idea is that you shouldn't have to hard-code execution logic. You declare capabilities (things the system can do), define which steps map to which goal keywords, and Nexora handles the rest: planning, scheduling, retrying on failure, and tracing what happened.

Three things set it apart from every other workflow engine:

- **Pluggable planner SPI** - the planner itself is a plugin. Swap in an LLM, a constraint solver, or your own rule engine. The built-in keyword matcher is just the default.
- **Reactive plan amendment** - a step can reshape the remaining plan based on what it produced. Inject new steps, skip pending ones, or override inputs for downstream steps, all at runtime without touching the planner.
- **Capability contracts with automatic fallback** - capabilities declare their expected latency and error rate. The engine monitors live call metrics and silently reroutes to a fallback capability when the primary starts breaching its contract.

## How it works

When you call `engine.execute("process order payment", context)`:

1. The **planner** matches the goal against registered step definitions and builds a DAG
2. The **scheduler** walks the DAG and starts every step whose dependencies are already done; independent steps run in parallel on virtual threads
3. Each step flows through an **interceptor pipeline** (tracing -> retry -> timeout) before hitting the actual capability
4. If a step returns **plan amendments**, the scheduler applies them before any dependent step begins
5. The **contract monitor** tracks every call outcome; if a capability breaches its declared SLA, traffic is rerouted to its fallback
6. Events fire as steps start, complete, fail, or when the plan is amended; you can subscribe to any of them

A concrete example: four steps, two run in parallel.

```
validate_order --+
                 +--> charge_card --> send_receipt
fetch_inventory -+
```

`validate_order` and `fetch_inventory` start at the same time. `charge_card` waits for `validate_order`. `send_receipt` waits for both. Nexora works all of this out from the `dependsOn` declarations. You don't schedule anything manually.

At runtime, `validate_order` can inject a new `audit_log` step into that DAG without the planner being involved at all.

## Requirements

- Java 21
- Maven 3.9+

## Build

```bash
mvn install -DskipTests
```

The CLI fat JAR ends up at `nexora-cli/target/nexora.jar`.

## Try it immediately

No config file needed. The `demo` command showcases all three differentiating features in a single run:

```bash
java -jar nexora-cli/target/nexora.jar demo
```

```
Nexora Feature Demo
===================

Features demonstrated:
  1. Pluggable planner SPI  - rule-based planner wired via CompositePlanner
  2. Reactive plan amendment - validate_order injects audit_log at runtime
  3. Capability contracts    - charge_card declares p99 SLA + fallback

Initial DAG (from planner):
  validate_order --+
                   +--> charge_card --> send_receipt
  fetch_inventory -+

  validate_order will amend the plan at runtime, injecting:
  --> audit_log (runs after validate_order, before send_receipt)

Execution:
  ✓ validate_order          37ms
  ~ plan amended: ADD_STEP   -> audit_log
  ~ plan amended: MODIFY_INPUT -> send_receipt
  ✓ fetch_inventory         54ms
  ✓ audit_log               16ms
  ✓ charge_card             86ms
  ✓ send_receipt            22ms

Result:   COMPLETED
Steps:    5 executed

Contract health (charge_card):
  samples=1  error-rate=0%  p99=86ms
```

The plan started with 4 steps and finished with 5. `validate_order` injected `audit_log` mid-run. `charge_card` was monitored against its declared p99=200ms SLA throughout.

## CLI

```
nexora [--config <file>] <command>
```

| Command | What it does |
|---------|-------------|
| `nexora run -g "<goal>"` | Execute an intent and stream step results |
| `nexora plan -g "<goal>"` | Dry run: show the DAG without executing anything |
| `nexora caps` | List all registered capabilities |
| `nexora plugins` | List active plugins |
| `nexora observe` | Start UI/API/metrics server for live process observability |
| `nexora demo` | Run the built-in feature demo |
| `nexora dlq list` | List dead letter queue entries (default: PENDING) |
| `nexora dlq replay <id>` | Replay a dead-lettered execution |
| `nexora dlq resolve <id>` | Mark a dead letter as resolved |

Pass `-c '{"key":"value"}'` to `run` to inject context values that steps can reference.

## Config file

By default Nexora looks for `nexora.json` in the working directory. Point to a different one with `--config`.

```json
{
  "steps": [
    {
      "id": "validate_order",
      "capabilityId": "validate_order",
      "matchesGoalContains": "order"
    },
    {
      "id": "charge_card",
      "capabilityId": "charge_card",
      "matchesGoalContains": "payment"
    }
  ],
  "retry": {
    "maxAttempts": 3,
    "initialDelayMs": 200,
    "multiplier": 2.0,
    "maxDelayMs": 10000
  }
}
```

A step is included in the plan when its `matchesGoalContains` string appears in the goal. Dependencies between steps are declared in code using `StepDefinition`'s `dependsOn` set.

## Using it as a library

```java
NexoraEngine engine = NexoraEngine.builder()
    .withPlugin(myPlugin)
    .withStepDefinition(new StepDefinition(
        "validate_order", "validate_order",
        goal -> goal.contains("order")
    ))
    .build();

engine.subscribe(StepCompletedEvent.class, e ->
    System.out.printf("done: %s in %dms%n", e.stepId(), e.elapsed().toMillis()));

ExecutionResult result = engine
    .execute("process order payment", Map.of("orderId", "ORD-99"))
    .get();
```

## Pluggable planner

The planner that converts a goal string into a DAG is itself a plugin. Implement `Planner` and return it from `plannerProviders()` in your `NexoraPlugin`:

```java
public class MySmartPlanner implements Planner {

    @Override
    public PlannerDescriptor descriptor() {
        return new PlannerDescriptor("my-planner", "LLM-backed planner", 100);
    }

    @Override
    public boolean canPlan(Intent intent, PlanningContext context) {
        return intent.getGoal().length() > 20; // handle complex goals
    }

    @Override
    public Plan plan(Intent intent, PlanningContext context) {
        // use context.availableCapabilities() to see what's registered
        // build and return a Plan
    }
}
```

The engine tries planners in descending priority order. The built-in rule-based planner always sits last as the fallback. Registering a planner with priority 100 means it runs first; if `canPlan()` returns false, the next one is tried.

You can also register a planner directly without a plugin:

```java
NexoraEngine.builder()
    .withPlanner(new MySmartPlanner())
    .build();
```

## Reactive plan amendment

A capability can reshape the remaining plan by returning amendments alongside its result:

```java
return CapabilityResult.success(
    Map.of("valid", true),
    List.of(
        // inject a new step that runs after this one
        new AddStepAmendment(new Step("audit_log", "audit_log",
            Map.of("orderId", InputBinding.literal(orderId)),
            null, Set.of("validate_order"), null, null)),

        // override an input for a downstream step
        new ModifyInputAmendment("send_receipt", "audited", true),

        // cancel a step that is no longer needed
        new SkipStepAmendment("legacy_check")
    )
);
```

Amendments are applied by the scheduler before any dependent step begins. A `PlanAmendedEvent` fires for each one so you can observe every mutation.

## Capability contracts

> **Note**: Stateful circuit breaker options (`openDuration` and `probeInterval`) are currently **Unreleased**.

Capabilities declare their expected operational behaviour. The engine monitors every call and reroutes traffic when a capability breaches its contract:

```java
new CapabilityDescriptor(
    "charge_card", "Charges the customer card",
    List.of(), List.of(), false, false,
    CapabilityContract.builder()
        .p99Latency(Duration.ofMillis(200))
        .maxErrorRate(0.05)
        .windowSize(20)
        .openDuration(Duration.ofSeconds(30))
        .probeInterval(Duration.ofSeconds(10))
        .fallback("charge_card_fallback")
        .build()
)
```

If `charge_card` starts exceeding 200ms p99 or failing more than 5% of the time over the last 20 calls, the engine silently opens the circuit and routes new calls to `charge_card_fallback`. The circuit remains `OPEN` for 30 seconds before transitioning to `HALF_OPEN`, where it probes the primary capability every 10 seconds. When the primary recovers, the circuit closes and traffic returns automatically. The caller sees a normal result either way.

Query live health at any time:

```java
NexoraEngine.HealthSnapshot health = NexoraEngine.HealthSnapshot.from(
    engine.capabilityHealth("charge_card"));
// health.state(), health.sampleCount(), health.errorRate(), health.p99Latency()
```

## Writing a plugin

A plugin is a JAR that implements `NexoraPlugin` and declares itself in `META-INF/services/com.nexora.spi.NexoraPlugin`.

```java
public class MyPlugin implements NexoraPlugin {

    @Override
    public PluginDescriptor descriptor() {
        return new PluginDescriptor("my-plugin", "1.0.0", "Does stuff", List.of(), null);
    }

    @Override
    public void initialize(PluginContext ctx) {}

    @Override
    public List<CapabilityProvider> capabilityProviders() {
        return List.of(
            new CapabilityProvider() {
                public CapabilityDescriptor descriptor() {
                    return new CapabilityDescriptor(
                        "my_capability", "My Capability",
                        List.of(), List.of(), true, false
                    );
                }
                public Capability create(PluginContext ctx) {
                    return request -> CapabilityResult.success(Map.of("result", "ok"));
                }
            }
        );
    }

    @Override
    public void shutdown() {}
}
```

Load a plugin JAR at runtime:

```java
engine.loadPlugin(Path.of("my-plugin.jar"), "my-plugin");
```

Or wire it directly without a JAR (useful in tests):

```java
NexoraEngine.builder().withPlugin(new MyPlugin()).build();
```

## Events

Subscribe to any event type:

```java
engine.subscribe(StepStartedEvent.class,   e -> log.info("started: {}", e.stepId()));
engine.subscribe(StepCompletedEvent.class, e -> log.info("done: {}", e.stepId()));
engine.subscribe(StepFailedEvent.class,    e -> log.error("failed: {} {}", e.stepId(), e.failureMessage()));
engine.subscribe(PlanAmendedEvent.class,   e -> log.info("plan mutated: {} -> {}", e.amendmentType(), e.targetStepId()));
engine.subscribe(PlanCompletedEvent.class, e -> metrics.record(e.elapsed()));
```

Event handlers run on the engine's executor, not the caller's thread. A handler that throws does not affect execution or other handlers.

## Retry

The default retry policy is no retry. Override it globally:

```java
NexoraEngine.builder()
    .withDefaultRetryPolicy(
        ExponentialBackoffPolicy.builder()
            .maxAttempts(3)
            .initialDelay(Duration.ofMillis(200))
            .multiplier(2.0)
            .maxDelay(Duration.ofSeconds(10))
            .build()
    )
    .build();
```

Backoff includes ±25% jitter to avoid thundering herd on simultaneous failures.

## Execution Deadline (Unreleased version)

Nexora allows you to set a plan-level wall-clock execution deadline. If the execution exceeds this duration, the entire plan is cancelled: running steps continue, but not-yet-started steps are suppressed, the terminal execution status is set to `TIMED_OUT`, and saga compensation is triggered for all successfully completed steps.

You can configure a global engine-wide default deadline using the builder:

```java
NexoraEngine engine = NexoraEngine.builder()
    .withDefaultPlanDeadline(Duration.ofSeconds(5))
    .build();
```

You can also override the deadline per execution request:

```java
// Sets a 2-second deadline specifically for this execution
CompletableFuture<ExecutionResult> future = engine.execute(
    "process order payment notification", 
    Map.of("orderId", "ORD-42"), 
    Duration.ofSeconds(2)
);
```

When a plan times out:
1. The execution status resolves to `TIMED_OUT`.
2. A `PlanTimedOutEvent` is published.
3. Saga compensation runs for completed steps if saga is enabled.

## Webhook Callbacks (Unreleased version)

Nexora allows you to register webhook URLs to be notified asynchronously when an execution reaches a terminal state (`COMPLETED`, `FAILED`, or `TIMED_OUT`). This is particularly useful when triggering executions remotely via the API and awaiting their outcome.

To use webhooks securely, configure an HMAC-SHA256 signature secret in your `nexora.json` or via the `NEXORA_WEBHOOK_SECRET` environment variable:

```json
{
  "webhookSecret": "your-secure-secret-here",
  "steps": []
}
```

When invoking an execution, supply the `webhookUrl` and optionally filter which terminal `webhookEvents` trigger a callback:

```bash
curl -X POST http://localhost:9464/api/execute \
  -H "content-type: application/json" \
  -d '{
        "goal": "process order payment",
        "webhookUrl": "https://your-api.com/webhooks/nexora",
        "webhookEvents": ["COMPLETED", "FAILED", "TIMED_OUT"]
      }'
```

Nexora will dispatch a JSON payload to your endpoint with the execution outcome. It signs the payload using the configured secret and passes the signature in the `nexora-signature` HTTP header for validation. It also utilizes exponential backoff to retry deliveries up to 3 attempts. Delivery attempts are persisted in the `nexora_webhook_deliveries` database table and can be queried for auditability via the API.

## Dead Letter Queue (Unreleased version)

When a execution fails after exhausting all retries, Nexora writes a record to the `nexora_dead_letters` table and fires an `ExecutionDeadLetteredEvent` on the event bus. This gives operators a structured audit trail of every permanently failed execution and a way to replay or resolve them without querying the database directly.

Each dead letter record carries:

| Field | Description |
|-------|-------------|
| `id` | UUID of the dead letter record |
| `executionId` | The original failed execution |
| `goal` | The intent goal string |
| `context` | The intent context (JSON) |
| `failureCode` | Machine-readable failure code (e.g. `STEP_FAILED`) |
| `failureMessage` | Human-readable error detail |
| `failedAt` | When the failure occurred |
| `reviewState` | `PENDING`, `RESOLVED`, or `REPLAYED` |

Subscribe to the event for alerting:

```java
engine.subscribe(ExecutionDeadLetteredEvent.class, e ->
    alerting.notify("Execution dead-lettered: " + e.executionId() + " code=" + e.failureCode()));
```

Inspect and remediate via CLI:

```bash
# list all pending dead letters
nexora dlq list

# replay a failed execution (creates a new execution with the same goal and context)
nexora dlq replay <dead-letter-id>

# mark as resolved when no replay is needed
nexora dlq resolve <dead-letter-id> --reason "Root cause fixed in deployment 1.2.3"
```

Or via the observability REST API:

```bash
# list PENDING (default)
curl http://localhost:9464/api/dead-letters

# filter by state, paginate
curl "http://localhost:9464/api/dead-letters?state=RESOLVED&page=0&size=10"

# replay
curl -X POST http://localhost:9464/api/dead-letters/<id>/replay

# resolve
curl -X POST http://localhost:9464/api/dead-letters/<id>/resolve \
  -H "content-type: application/json" \
  -d '{"reason":"investigated and closed"}'
```

> **Authentication**: DLQ endpoints will require a Bearer token once [#30](https://github.com/iamvirul/nexora/issues/30) lands.

## Observability UI + Prometheus + Grafana

Start Nexora's built-in observability server:

```bash
java -jar nexora-cli/target/nexora.jar observe --port 9464
```

This exposes four endpoints with no external dependencies:

| Endpoint | What it serves |
|----------|---------------|
| `GET /` | Live process UI showing active executions, step timelines, and plan amendments |
| `GET /metrics` | Prometheus text format scrape endpoint |
| `GET /api/process` | Raw process snapshot as JSON |
| `POST /api/execute` | Trigger an execution remotely |
| `GET /health/ready` | Check health of all capabilities (returns 503 if any circuit is OPEN/HALF_OPEN) |
| `GET /api/webhook-deliveries/{id}` | Audit log of webhook delivery attempts for an execution |
| `GET /api/dead-letters` | List dead letter queue entries (paginated, filterable by `?state=PENDING\|RESOLVED\|REPLAYED\|ALL`) |
| `POST /api/dead-letters/{id}/replay` | Create a new execution from a dead letter and mark it as `REPLAYED` |
| `POST /api/dead-letters/{id}/resolve` | Mark a dead letter as `RESOLVED` with an optional `{"reason":"..."}` body |

> **Note**: The `/health/ready` endpoint is currently **Unreleased**.

Example execute request:

```bash
curl -X POST http://localhost:9464/api/execute \
  -H "content-type: application/json" \
  -d '{"goal":"process order payment notification","context":{"orderId":"ORD-99"}}'
```

Metrics exposed include:

- `nexora_plan_started_total`, `nexora_plan_completed_total`, `nexora_plan_failed_total`
- `nexora_plan_duration_seconds` — histogram by status (completed/failed)
- `nexora_step_started_total`, `nexora_step_completed_total`, `nexora_step_failed_total` — all by capability ID
- `nexora_step_duration_seconds` — histogram by capability ID and terminal status
- `nexora_plan_amendments_total` — by amendment type (ADD_STEP, SKIP_STEP, MODIFY_INPUT)
- `nexora_active_executions` — current in-flight count

Bring up Prometheus, Grafana, and Alertmanager with the prebuilt stack:

```bash
cd observability
docker compose up -d
```

- Prometheus: `http://localhost:9090`
- Alertmanager: `http://localhost:9093`
- Grafana: `http://localhost:3000` (login: `admin` / `admin`)

The Grafana dashboard and alert rules are provisioned automatically. Provisioned assets:

- `observability/prometheus/prometheus.yml`
- `observability/prometheus/alerts.yml`
- `observability/grafana/dashboards/nexora-overview.json`

You can also attach observability to an engine in your own application without the HTTP server:

```java
NexoraEngine engine = NexoraEngine.builder()...build();

try (NexoraObservability obs = NexoraObservability.attach(engine)) {
    engine.execute("process order", context).get();
    String metrics = obs.scrapePrometheus();           // Prometheus text format
    ProcessSnapshot snapshot = obs.processSnapshot();  // live execution state
}
```

## Module layout

```
nexora-core           Domain types: Intent, Plan, Step, PlanAmendment, ExecutionContext
nexora-plugin-spi     Plugin contract: NexoraPlugin, Capability, Planner, CapabilityRegistry
nexora-registry       DefaultCapabilityRegistry (thread-safe, read-write locked)
nexora-tracing        Tracer/Span interfaces + no-op implementation
nexora-retry          RetryPolicy, ExponentialBackoffPolicy with jitter
nexora-event          ExecutionEvent sealed hierarchy, InProcessEventBus
nexora-executor       DAG scheduler with amendment support, interceptor pipeline, contract monitor
nexora-plugin-loader  PluginClassLoader, PluginManager, lifecycle FSM
nexora-planner        CompositePlanner, RulePlanner, StepDefinition, PlanRegistry
nexora-runtime        ExecutionEngine (ties planner + scheduler together)
nexora-api            NexoraEngine public facade, builder, NexoraObservability
nexora-cli            PicoCLI command-line interface + ObserveCommand HTTP server
```

Dependencies always point inward. `nexora-cli` knows about `nexora-api`. `nexora-api` knows about `nexora-runtime`. Neither knows anything about the CLI.
