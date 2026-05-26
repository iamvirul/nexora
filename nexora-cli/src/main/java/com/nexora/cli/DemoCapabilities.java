package com.nexora.cli;

import com.nexora.api.NexoraEngine;
import com.nexora.core.capability.CapabilityContract;
import com.nexora.core.capability.CapabilityResult;
import com.nexora.core.plan.AddStepAmendment;
import com.nexora.core.plan.InputBinding;
import com.nexora.core.plan.ModifyInputAmendment;
import com.nexora.core.plan.Step;
import com.nexora.planner.model.StepDefinition;
import com.nexora.spi.Capability;
import com.nexora.spi.CapabilityDescriptor;
import com.nexora.spi.CapabilityProvider;
import com.nexora.spi.NexoraPlugin;
import com.nexora.spi.PluginContext;
import com.nexora.spi.PluginDescriptor;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Order-processing demo capabilities shared by DemoCommand and the default observe engine.
 * Wires validate_order, fetch_inventory, charge_card (+fallback), send_receipt, audit_log.
 */
final class DemoCapabilities {

    private DemoCapabilities() {}

    static void wire(NexoraEngine.Builder builder) {
        builder
            .withPlugin(buildPlugin())
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
                    null, Set.of("charge_card", "fetch_inventory"), null, null));
    }

    static NexoraPlugin buildPlugin() {
        return new NexoraPlugin() {
            @Override public PluginDescriptor descriptor() {
                return new PluginDescriptor("demo-order-plugin", "1.0.0",
                        "Demo order capabilities", List.of(), null);
            }
            @Override public void initialize(PluginContext ctx) {}
            @Override public void shutdown() {}

            @Override public List<CapabilityProvider> capabilityProviders() {
                return List.of(
                    cap("validate_order", CapabilityContract.none(), req -> {
                        sleep(30);
                        Step auditStep = new Step(
                                "audit_log", "audit_log",
                                Map.of("orderId", InputBinding.literal(req.inputs().get("orderId"))),
                                null, Set.of("validate_order"), null, null, null, null);
                        return CapabilityResult.success(
                                Map.of("valid", true),
                                List.of(
                                    new AddStepAmendment(auditStep),
                                    new ModifyInputAmendment("send_receipt", "audited", true)
                                )
                        );
                    }),
                    cap("fetch_inventory", CapabilityContract.none(), req -> {
                        sleep(50);
                        return CapabilityResult.success(Map.of("stock", 10));
                    }),
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
                    cap("charge_card_fallback", CapabilityContract.none(), req -> {
                        sleep(40);
                        return CapabilityResult.success(
                                Map.of("chargeId", "FALLBACK-CHG-" + Instant.now().toEpochMilli()));
                    }),
                    cap("send_receipt", CapabilityContract.none(), req -> {
                        sleep(20);
                        return CapabilityResult.success("sent");
                    }),
                    cap("audit_log", CapabilityContract.none(), req -> {
                        sleep(15);
                        return CapabilityResult.success("logged");
                    })
                );
            }
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

    static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
