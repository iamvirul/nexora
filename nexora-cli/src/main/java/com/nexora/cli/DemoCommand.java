package com.nexora.cli;

import com.nexora.api.NexoraEngine;
import com.nexora.core.capability.CapabilityResult;
import com.nexora.core.execution.ExecutionResult;
import com.nexora.core.execution.ExecutionStatus;
import com.nexora.core.intent.Intent;
import com.nexora.core.plan.InputBinding;
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

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;

@Command(
        name = "demo",
        description = "Run a built-in order-processing demo showcasing DAG execution.",
        mixinStandardHelpOptions = true
)
public class DemoCommand implements Callable<Integer> {

    @Option(names = {"--order-id"}, defaultValue = "ORD-42",
            description = "Order ID to use in the demo. Default: ${DEFAULT-VALUE}")
    private String orderId;

    @Override
    public Integer call() throws Exception {
        System.out.println("╔══════════════════════════════════════════╗");
        System.out.println("║        Nexora — DAG Execution Demo        ║");
        System.out.println("╚══════════════════════════════════════════╝");
        System.out.println();
        System.out.println("Plan:");
        System.out.println("  validate_order ──┐");
        System.out.println("                   ├─► charge_card ─► send_receipt");
        System.out.println("  fetch_inventory ─┘");
        System.out.println();

        NexoraPlugin plugin = buildOrderPlugin();

        NexoraEngine engine = NexoraEngine.builder()
                .withPlugin(plugin)
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
                System.out.printf("  ✓ %-20s  %dms%n", e.stepId(), e.elapsed().toMillis()));
        engine.subscribe(StepFailedEvent.class, e ->
                System.out.printf("  ✗ %-20s  [%s] %s%n", e.stepId(), e.failureCode(), e.failureMessage()));

        System.out.println("Execution:");
        ExecutionResult result = engine
                .execute("process order payment notification", Map.of("orderId", orderId))
                .get();

        System.out.println();
        System.out.printf("Result:  %s%n", result.status());
        System.out.printf("Trace:   %s%n", result.executionId());

        return result.status() == ExecutionStatus.COMPLETED ? 0 : 1;
    }

    private static NexoraPlugin buildOrderPlugin() {
        return new NexoraPlugin() {
            @Override public PluginDescriptor descriptor() {
                return new PluginDescriptor("demo-order-plugin", "1.0.0",
                        "Demo order capabilities", List.of(), null);
            }
            @Override public void initialize(PluginContext ctx) {}
            @Override public List<CapabilityProvider> capabilityProviders() {
                return List.of(
                        cap("validate_order", req -> {
                            sleep(30);
                            return CapabilityResult.success(Map.of("valid", true));
                        }),
                        cap("fetch_inventory", req -> {
                            sleep(50);
                            return CapabilityResult.success(Map.of("stock", 10));
                        }),
                        cap("charge_card", req -> {
                            sleep(80);
                            return CapabilityResult.success(
                                    Map.of("chargeId", "CHG-" + Instant.now().toEpochMilli()));
                        }),
                        cap("send_receipt", req -> {
                            sleep(20);
                            return CapabilityResult.success("sent");
                        })
                );
            }
            @Override public void shutdown() {}
        };
    }

    private static CapabilityProvider cap(String id, Capability impl) {
        return new CapabilityProvider() {
            @Override public CapabilityDescriptor descriptor() {
                return new CapabilityDescriptor(id, id, List.of(), List.of(), true, false);
            }
            @Override public Capability create(PluginContext ctx) { return impl; }
        };
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
