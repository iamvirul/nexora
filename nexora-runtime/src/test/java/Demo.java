import com.nexora.core.capability.CapabilityResult;
import com.nexora.core.execution.ExecutionResult;
import com.nexora.core.intent.Intent;
import com.nexora.core.plan.InputBinding;
import com.nexora.event.InProcessEventBus;
import com.nexora.event.PlanCompletedEvent;
import com.nexora.event.StepCompletedEvent;
import com.nexora.event.StepFailedEvent;
import com.nexora.executor.CapabilityInvoker;
import com.nexora.executor.DagStepScheduler;
import com.nexora.executor.InterceptorPipeline;
import com.nexora.executor.interceptor.RetryInterceptor;
import com.nexora.executor.interceptor.TimeoutInterceptor;
import com.nexora.executor.interceptor.TracingInterceptor;
import com.nexora.loader.PluginManager;
import com.nexora.planner.engine.PlannerEngine;
import com.nexora.planner.model.StepDefinition;
import com.nexora.planner.registry.PlanRegistry;
import com.nexora.registry.DefaultCapabilityRegistry;
import com.nexora.retry.DefaultRetryPolicyRegistry;
import com.nexora.retry.ExponentialBackoffPolicy;
import com.nexora.retry.RetryPolicyRegistry;
import com.nexora.runtime.engine.ExecutionEngine;
import com.nexora.spi.*;
import com.nexora.tracing.NoopTracer;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;

/**
 * Integration demo showing the full v2 architecture:
 *  - Inline plugin with 3 capabilities
 *  - DAG plan: validate_order and fetch_inventory run in parallel;
 *    charge_card waits for validate_order; send_receipt waits for both
 *  - Exponential backoff retry policy
 *  - Event bus subscriptions for observability
 */
public class Demo {

    public static void main(String[] args) throws Exception {

        var executor = Executors.newVirtualThreadPerTaskExecutor();

        // 1. INFRASTRUCTURE
        CapabilityRegistry capabilityRegistry = new DefaultCapabilityRegistry();
        InProcessEventBus eventBus = new InProcessEventBus(executor);

        // Subscribe to events for demo output
        eventBus.subscribe(StepCompletedEvent.class, e ->
                System.out.printf("  [EVENT] StepCompleted  step=%-20s elapsed=%dms%n",
                        e.stepId(), e.elapsed().toMillis()));

        eventBus.subscribe(StepFailedEvent.class, e ->
                System.out.printf("  [EVENT] StepFailed     step=%-20s code=%s msg=%s%n",
                        e.stepId(), e.failureCode(), e.failureMessage()));

        eventBus.subscribe(PlanCompletedEvent.class, e ->
                System.out.printf("  [EVENT] PlanCompleted  executionId=%s elapsed=%dms%n",
                        e.executionId(), e.elapsed().toMillis()));

        //  2. PLUGIN WITH INLINE CAPABILITIES
        NexoraPlugin orderPlugin = new NexoraPlugin() {
            @Override public PluginDescriptor descriptor() {
                return new PluginDescriptor("order-plugin", "1.0.0", "Order processing capabilities",
                        List.of(), null);
            }
            @Override public void initialize(PluginContext ctx) {
                ctx.logger().info("Order plugin initialising");
            }
            @Override public List<CapabilityProvider> capabilityProviders() {
                return List.of(
                        provider("validate_order",   req -> {
                            System.out.println("  [CAP] Validating order " + req.inputs().get("orderId"));
                            simulate(30);
                            return CapabilityResult.success(Map.of("valid", true));
                        }),
                        provider("fetch_inventory",  req -> {
                            System.out.println("  [CAP] Fetching inventory");
                            simulate(50);  // runs in parallel with validate_order
                            return CapabilityResult.success(Map.of("stock", 10));
                        }),
                        provider("charge_card",      req -> {
                            System.out.println("  [CAP] Charging card for order " + req.inputs().get("orderId"));
                            simulate(80);
                            return CapabilityResult.success(Map.of("chargeId", "CHG-" + Instant.now().toEpochMilli()));
                        }),
                        provider("send_receipt",     req -> {
                            System.out.println("  [CAP] Sending receipt chargeId=" + req.inputs().get("chargeId"));
                            simulate(20);
                            return CapabilityResult.success("receipt_sent");
                        })
                );
            }
            @Override public void shutdown() {}
        };

        PluginManager pluginManager = new PluginManager(capabilityRegistry, eventBus);
        pluginManager.registerPlugin(orderPlugin);
        pluginManager.activatePlugin("order-plugin");

        // 3. RETRY POLICY
        RetryPolicyRegistry retryPolicyRegistry = new DefaultRetryPolicyRegistry();
        retryPolicyRegistry.setDefault(
                ExponentialBackoffPolicy.builder()
                        .maxAttempts(3)
                        .initialDelay(Duration.ofMillis(100))
                        .multiplier(2.0)
                        .maxDelay(Duration.ofSeconds(2))
                        .build()
        );

        // 4. EXECUTION PIPELINE
        List<com.nexora.executor.ExecutionInterceptor> interceptors = List.of(
                new TracingInterceptor(NoopTracer.INSTANCE),
                new RetryInterceptor(retryPolicyRegistry),
                new TimeoutInterceptor(executor, Duration.ofSeconds(10))
        );
        InterceptorPipeline pipeline = new InterceptorPipeline(
                interceptors,
                new CapabilityInvoker(capabilityRegistry, new com.nexora.executor.CapabilityContractMonitor())
        );
        DagStepScheduler scheduler = new DagStepScheduler(pipeline, retryPolicyRegistry, eventBus, executor);

        // 5. PLANNER — DAG WITH PARALLEL STEPS
        PlanRegistry planRegistry = new PlanRegistry();

        planRegistry.register(new StepDefinition(
                "validate_order", "validate_order",
                goal -> goal.contains("order"),
                Map.of("orderId", InputBinding.fromContext("intent.context.orderId")),
                "validation_result",
                Set.of(),   // no deps — runs immediately
                null, null
        ));

        planRegistry.register(new StepDefinition(
                "fetch_inventory", "fetch_inventory",
                goal -> goal.contains("order"),
                Map.of(),
                "inventory_result",
                Set.of(),   // no deps — runs in parallel with validate_order
                null, null
        ));

        planRegistry.register(new StepDefinition(
                "charge_card", "charge_card",
                goal -> goal.contains("payment"),
                Map.of("orderId", InputBinding.fromContext("intent.context.orderId")),
                "charge_result",
                Set.of("validate_order"),   // waits for validate_order
                null, null
        ));

        planRegistry.register(new StepDefinition(
                "send_receipt", "send_receipt",
                goal -> goal.contains("notification"),
                Map.of("chargeId", InputBinding.fromStep("charge_card", "chargeId")),
                null,
                Set.of("charge_card", "fetch_inventory"),  // waits for both
                null, null
        ));

        PlannerEngine plannerEngine = new PlannerEngine(planRegistry);
        com.nexora.planner.engine.RulePlanner rulePlanner = new com.nexora.planner.engine.RulePlanner(plannerEngine);
        com.nexora.planner.engine.CompositePlanner compositePlanner =
                new com.nexora.planner.engine.CompositePlanner(List.of(), rulePlanner);

        // 6. EXECUTION ENGINE
        ExecutionEngine engine = new ExecutionEngine(compositePlanner, capabilityRegistry, scheduler, eventBus);

        // 7. RUN
        Intent intent = new Intent(
                "process order payment notification",
                Map.of("orderId", "ORD-789")
        );

        System.out.println("\n=== NEXORA v2 — EXECUTION START ===");
        System.out.println("Goal: " + intent.getGoal());
        System.out.println("Plan DAG: validate_order ─┐");
        System.out.println("                           ├─ charge_card ─ send_receipt");
        System.out.println("          fetch_inventory ─┘\n");

        ExecutionResult result = engine.execute(intent).get();

        System.out.println("\n=== RESULT ===");
        System.out.println("Status:       " + result.status());
        System.out.println("ExecutionId:  " + result.executionId());
        System.out.println("Steps:");
        result.stepResults().forEach(sr ->
                System.out.printf("  %-20s %s%n", sr.stepId(), sr.getStatus()));

        executor.shutdown();
    }

    private static CapabilityProvider provider(String id, Capability impl) {
        return new CapabilityProvider() {
            @Override public CapabilityDescriptor descriptor() {
                return new CapabilityDescriptor(id, id, List.of(), List.of(), true, false);
            }
            @Override public Capability create(PluginContext ctx) { return impl; }
        };
    }

    private static void simulate(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}