package me.virul.nexora.planner.llm;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import me.virul.nexora.spi.CapabilityDescriptor;
import me.virul.nexora.spi.ParameterDescriptor;
import me.virul.nexora.spi.PlanningContext;

/** An order workflow's capabilities and a plan over them, shared by the planner tests. */
final class Fixtures {

    static final String GOAL = "process order and email a receipt";

    static final List<CapabilityDescriptor> CAPABILITIES = List.of(
            capability("validate_order", List.of(param("orderId", true))),
            capability("charge_card", List.of(param("orderId", true), param("amount", true))),
            capability("send_receipt", List.of(param("orderId", true), param("chargeId", false))));

    /** validate_order, then charge_card, then send_receipt with the charge id. */
    static final String VALID_PLAN = """
            {"steps": [
              {"id": "validate", "capabilityId": "validate_order", "dependsOn": [],
               "inputs": [{"name": "orderId", "source": "context", "value": "orderId", "stepId": null, "field": null}]},
              {"id": "charge", "capabilityId": "charge_card", "dependsOn": ["validate"],
               "inputs": [{"name": "orderId", "source": "context", "value": "orderId", "stepId": null, "field": null},
                          {"name": "amount", "source": "literal", "value": "42.00", "stepId": null, "field": null}]},
              {"id": "receipt", "capabilityId": "send_receipt", "dependsOn": [],
               "inputs": [{"name": "orderId", "source": "context", "value": "orderId", "stepId": null, "field": null},
                          {"name": "chargeId", "source": "step", "value": null, "stepId": "charge", "field": "id"}]}
            ]}""";

    private Fixtures() {}

    static PlanningContext context() {
        return context(CAPABILITIES);
    }

    static PlanningContext context(List<CapabilityDescriptor> capabilities) {
        return new PlanningContext() {
            @Override
            public List<CapabilityDescriptor> availableCapabilities() {
                return capabilities;
            }

            @Override
            public Map<String, Object> config() {
                return Map.of();
            }
        };
    }

    static LlmPlannerConfig config(LlmProvider provider, String model, String baseUrl) {
        return new LlmPlannerConfig(
                provider,
                "test-key",
                model,
                LlmPlannerConfig.DEFAULT_MAX_TOKENS,
                null,
                null,
                baseUrl,
                Duration.ofSeconds(10),
                0,
                LlmPlannerConfig.DEFAULT_CACHE_TTL,
                LlmPlannerConfig.DEFAULT_CACHE_MAX_ENTRIES,
                LlmPlannerConfig.DEFAULT_PRIORITY);
    }

    private static CapabilityDescriptor capability(String id, List<ParameterDescriptor> inputs) {
        return new CapabilityDescriptor(id, "Runs " + id, inputs, List.of(), true, false);
    }

    private static ParameterDescriptor param(String name, boolean required) {
        return new ParameterDescriptor(name, "string", required, null);
    }
}
