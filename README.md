# Nexora

Nexora is a Java execution engine that turns a high-level goal into a set of steps and runs them. You tell it what you want to happen, and it figures out the order, runs independent steps in parallel, and gives you back a result.

The idea is that you shouldn't have to hard-code execution logic. You declare capabilities (things the system can do), define which steps map to which goal keywords, and Nexora handles the rest: planning, scheduling, retrying on failure, and tracing what happened.

## How it works

When you call `engine.execute("process order payment", context)`:

1. The **planner** matches the goal string against your registered step definitions and builds a DAG
2. The **scheduler** walks the DAG and starts every step whose dependencies are already done; independent steps run in parallel on virtual threads
3. Each step flows through an **interceptor pipeline** (tracing → retry → timeout) before hitting the actual capability
4. Results from one step can be passed as inputs to the next using output bindings
5. Events fire as steps start, complete, or fail; you can subscribe to any of them

A concrete example: four steps, two of them run in parallel, wall time is less than running them sequentially.

```
validate_order --+
                 +--> charge_card --> send_receipt
fetch_inventory -+
```

`validate_order` and `fetch_inventory` start at the same time. `charge_card` waits for `validate_order`. `send_receipt` waits for both `charge_card` and `fetch_inventory`. Nexora works all of this out from the `dependsOn` declarations. You don't schedule anything manually.

## Requirements

- Java 21
- Maven 3.9+

## Build

```bash
mvn install -DskipTests
```

The CLI fat JAR ends up at `nexora-cli/target/nexora.jar`.

## Try it immediately

No config file needed. The `demo` command wires four in-memory capabilities and runs the order-processing DAG shown above:

```bash
java -jar nexora-cli/target/nexora.jar demo
```

```
Plan:
  validate_order --+
                   +--> charge_card --> send_receipt
  fetch_inventory -+

Execution:
  ✓ validate_order        31ms
  ✓ fetch_inventory       51ms
  ✓ charge_card           81ms
  ✓ send_receipt          21ms

Result:  COMPLETED
Trace:   a3f1c2d4-...
```

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
| `nexora demo` | Run the built-in order-processing demo |

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

## Writing a plugin

A plugin is a JAR that implements `NexoraPlugin` and declares itself in `META-INF/services/com.nexora.spi.NexoraPlugin`.

```java
public class MyPlugin implements NexoraPlugin {

    @Override
    public PluginDescriptor descriptor() {
        return new PluginDescriptor("my-plugin", "1.0.0", "Does stuff", List.of(), null);
    }

    @Override
    public void initialize(PluginContext ctx) {
        // acquire resources, read config from ctx.getConfig()
    }

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
                    return request -> {
                        // do the work
                        return CapabilityResult.success(Map.of("result", "ok"));
                    };
                }
            }
        );
    }

    @Override
    public void shutdown() {
        // release resources
    }
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

## Module layout

```
nexora-core           Domain types: Intent, Plan, Step, ExecutionContext
nexora-plugin-spi     Plugin contract: NexoraPlugin, Capability, CapabilityRegistry
nexora-registry       DefaultCapabilityRegistry (thread-safe)
nexora-tracing        Tracer/Span interfaces + no-op implementation
nexora-retry          RetryPolicy, ExponentialBackoffPolicy with jitter
nexora-event          ExecutionEvent hierarchy, InProcessEventBus
nexora-executor       DAG scheduler, interceptor pipeline
nexora-plugin-loader  PluginClassLoader, PluginManager, lifecycle FSM
nexora-planner        PlannerEngine, StepDefinition, PlanRegistry
nexora-runtime        ExecutionEngine (ties planner + scheduler together)
nexora-api            NexoraEngine public facade and builder
nexora-cli            PicoCLI command-line interface
```

Dependencies always point inward. `nexora-cli` knows about `nexora-api`. `nexora-api` knows about `nexora-runtime`. Neither knows anything about the CLI.

## Events

Subscribe to any event type:

```java
engine.subscribe(StepStartedEvent.class,   e -> log.info("started: {}", e.stepId()));
engine.subscribe(StepCompletedEvent.class, e -> log.info("done: {}", e.stepId()));
engine.subscribe(StepFailedEvent.class,    e -> log.error("failed: {} {}", e.stepId(), e.failureMessage()));
engine.subscribe(PlanCompletedEvent.class, e -> metrics.record(e.elapsed()));
```

Event handlers run on the engine's executor, not the caller's thread. A handler that throws does not affect the execution or other handlers.

## Retry

The default retry policy is no retry. Override it globally or per step:

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
