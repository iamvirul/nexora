package com.nexora.cli;

import com.nexora.api.NexoraEngine;
import com.nexora.core.capability.CapabilityContract;
import com.nexora.core.capability.CapabilityResult;
import com.nexora.core.execution.ExecutionResult;
import com.nexora.core.execution.ExecutionStatus;
import com.nexora.core.intent.Intent;
import com.nexora.core.plan.AddStepAmendment;
import com.nexora.core.plan.InputBinding;
import com.nexora.core.plan.ModifyInputAmendment;
import com.nexora.core.plan.Step;
import com.nexora.event.PlanAmendedEvent;
import com.nexora.event.StepCompletedEvent;
import com.nexora.event.StepFailedEvent;
import com.nexora.planner.model.StepDefinition;
import com.nexora.spi.Capability;
import com.nexora.spi.CapabilityDescriptor;
import com.nexora.spi.CapabilityProvider;
import com.nexora.spi.NexoraPlugin;
import com.nexora.spi.PluginContext;
import com.nexora.spi.PluginDescriptor;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;

@Command(
        name = "demo",
        // description loaded from help/demo.help at startup via HelpLoader
        mixinStandardHelpOptions = true
)
public class DemoCommand implements Callable<Integer> {

    @Option(names = {"--order-id"}, defaultValue = "ORD-42",
            description = "Order ID to use in the demo. Default: ${DEFAULT-VALUE}")
    private String orderId;

    @Override
    public Integer call() throws Exception {
        System.out.println("Nexora Feature Demo");
        System.out.println("===================");
        System.out.println();

        System.out.println("Features demonstrated:");
        System.out.println("  1. Pluggable planner SPI  - rule-based planner wired via CompositePlanner");
        System.out.println("  2. Reactive plan amendment - validate_order injects audit_log at runtime");
        System.out.println("  3. Capability contracts    - charge_card declares p99 SLA + fallback");
        System.out.println();

        System.out.println("Initial DAG (from planner):");
        System.out.println("  validate_order --+");
        System.out.println("                   +--> charge_card --> send_receipt");
        System.out.println("  fetch_inventory -+");
        System.out.println();
        System.out.println("  validate_order will amend the plan at runtime, injecting:");
        System.out.println("  --> audit_log (runs after validate_order, before send_receipt)");
        System.out.println();

        NexoraEngine engine = NexoraEngine.builder()
                .withPlugin(buildOrderPlugin())
                .withStepDefinition(new StepDefinition(
                        "validate_order", "validate_order",
                        g -> g.contains("order"),
                        Map.of("orderId", InputBinding.fromContext("intent.context.orderId")),
                        "validation", Set.of(), null, null))
                .withStepDefinition(new StepDefinition(
                        "fetch_inventory", "fetch_inventory",
                        g -> g.contains("order"),
                        Map.of(), "inventory", Set.of(), null, null))
                .withStepDefinition(new StepDefinition(
                        "charge_card", "charge_card",
                        g -> g.contains("payment"),
                        Map.of("orderId", InputBinding.fromContext("intent.context.orderId")),
                        "charge", Set.of("validate_order"), null, null))
                .withStepDefinition(new StepDefinition(
                        "send_receipt", "send_receipt",
                        g -> g.contains("notification"),
                        Map.of("chargeId", InputBinding.fromStep("charge_card", "chargeId")),
                        null, Set.of("charge_card", "fetch_inventory"), null, null))
                .build();

        engine.subscribe(StepCompletedEvent.class, e ->
                System.out.printf("  ✓ %-22s  %dms%n", e.stepId(), e.elapsed().toMillis()));
        engine.subscribe(StepFailedEvent.class, e ->
                System.out.printf("  ✗ %-22s  [%s] %s%n", e.stepId(), e.failureCode(), e.failureMessage()));
        engine.subscribe(PlanAmendedEvent.class, e ->
                System.out.printf("  ~ plan amended: %-10s -> %s%n", e.amendmentType(), e.targetStepId()));

        System.out.println("Execution:");
        ExecutionResult result = engine
                .execute("process order payment notification", Map.of("orderId", orderId))
                .get();

        System.out.println();
        System.out.printf("Result:   %s%n", result.status());
        System.out.printf("Steps:    %d executed%n", result.stepResults().size());
        System.out.printf("Trace:    %s%n", result.executionId());

        System.out.println();
        System.out.println("Contract health (charge_card):");
        NexoraEngine.HealthSnapshot health = NexoraEngine.HealthSnapshot.from(engine.capabilityHealth("charge_card"));
        System.out.printf("  samples=%d  error-rate=%.0f%%  p99=%dms%n",
                health.sampleCount(),
                health.errorRate() * 100,
                health.p99Latency().toMillis());

        return result.status() == ExecutionStatus.COMPLETED ? 0 : 1;
    }

    private NexoraPlugin buildOrderPlugin() {
        return new NexoraPlugin() {
            @Override public PluginDescriptor descriptor() {
                return new PluginDescriptor("demo-order-plugin", "1.0.0",
                        "Demo order capabilities", List.of(), null);
            }
            @Override public void initialize(PluginContext ctx) {}
            @Override public List<CapabilityProvider> capabilityProviders() {
                return List.of(
                        // validate_order: succeeds and injects audit_log + modifies send_receipt input
                        cap("validate_order",
                                CapabilityContract.none(),
                                req -> {
                                    sleep(30);
                                    Step auditStep = new Step(
                                            "audit_log", "audit_log",
                                            Map.of("orderId", InputBinding.literal(req.inputs().get("orderId"))),
                                            null,
                                            Set.of("validate_order"),
                                            null, null
                                    );
                                    return CapabilityResult.success(
                                            Map.of("valid", true),
                                            List.of(
                                                    new AddStepAmendment(auditStep),
                                                    new ModifyInputAmendment("send_receipt", "audited", true)
                                            )
                                    );
                                }),
                        cap("fetch_inventory",
                                CapabilityContract.none(),
                                req -> {
                                    sleep(50);
                                    return CapabilityResult.success(Map.of("stock", 10));
                                }),
                        // charge_card: has a p99=200ms SLA and a fallback
                        cap("charge_card",
                                CapabilityContract.builder()
                                        .p99Latency(Duration.ofMillis(200))
                                        .maxErrorRate(0.1)
                                        .fallback("charge_card_fallback")
                                        .build(),
                                req -> {
                                    sleep(80);
                                    return CapabilityResult.success(
                                            Map.of("chargeId", "CHG-" + Instant.now().toEpochMilli()));
                                }),
                        cap("charge_card_fallback",
                                CapabilityContract.none(),
                                req -> {
                                    sleep(40);
                                    return CapabilityResult.success(
                                            Map.of("chargeId", "FALLBACK-CHG-" + Instant.now().toEpochMilli()));
                                }),
                        cap("send_receipt",
                                CapabilityContract.none(),
                                req -> {
                                    sleep(20);
                                    return CapabilityResult.success("sent");
                                }),
                        cap("audit_log",
                                CapabilityContract.none(),
                                req -> {
                                    sleep(15);
                                    return CapabilityResult.success("logged");
                                })
                );
            }
            @Override public void shutdown() {}
        };
    }

    private static CapabilityProvider cap(String id, CapabilityContract contract, Capability impl) {
        return new CapabilityProvider() {
            @Override public CapabilityDescriptor descriptor() {
                return new CapabilityDescriptor(id, id, List.of(), List.of(), true, false, contract);
            }
            @Override public Capability create(PluginContext ctx) { return impl; }
        };
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
