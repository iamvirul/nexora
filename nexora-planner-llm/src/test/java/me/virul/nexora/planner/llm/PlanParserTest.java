package me.virul.nexora.planner.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import me.virul.nexora.core.plan.ContextBinding;
import me.virul.nexora.core.plan.LiteralBinding;
import me.virul.nexora.core.plan.Plan;
import me.virul.nexora.core.plan.Step;
import me.virul.nexora.core.plan.StepOutputBinding;
import me.virul.nexora.spi.CapabilityDescriptor;
import me.virul.nexora.spi.ParameterDescriptor;
import org.junit.jupiter.api.Test;

class PlanParserTest {

    private static final Set<String> CONTEXT_NAMES = Set.of("orderId");

    @Test
    void validAnswerBecomesAPlanWithItsBindings() {
        Plan plan = PlanParser.parse(Fixtures.VALID_PLAN, Fixtures.CAPABILITIES, CONTEXT_NAMES);

        Map<String, Step> steps = plan.getSteps().stream().collect(Collectors.toMap(Step::id, Function.identity()));
        assertThat(steps).containsOnlyKeys("validate", "charge", "receipt");
        assertThat(steps.get("charge").capabilityId()).isEqualTo("charge_card");
        assertThat(steps.get("charge").dependsOn()).containsExactly("validate");
        assertThat(steps.get("charge").inputs())
                .containsEntry("orderId", new ContextBinding("intent.context.orderId"))
                .containsEntry("amount", new LiteralBinding("42.00"));
        assertThat(steps.get("receipt").inputs()).containsEntry("chargeId", new StepOutputBinding("charge", "id"));
    }

    @Test
    void stepOutputBindingMakesThatStepADependency() {
        Plan plan = PlanParser.parse(Fixtures.VALID_PLAN, Fixtures.CAPABILITIES, CONTEXT_NAMES);

        Step receipt = plan.getSteps().stream()
                .filter(s -> s.id().equals("receipt"))
                .findFirst()
                .orElseThrow();
        assertThat(receipt.dependsOn()).containsExactly("charge");
    }

    @Test
    void contextNameTheRequestDoesNotCarryIsRejected() {
        assertInvalid(
                Fixtures.VALID_PLAN.replace("\"value\": \"orderId\"", "\"value\": \"customerId\""),
                "context value 'customerId'");
    }

    @Test
    void engineContextPrefixFromTheModelIsAccepted() {
        Plan plan = PlanParser.parse(
                Fixtures.VALID_PLAN.replace("\"value\": \"orderId\"", "\"value\": \"intent.context.orderId\""),
                Fixtures.CAPABILITIES,
                CONTEXT_NAMES);

        assertThat(plan.getSteps().get(0).inputs())
                .containsEntry("orderId", new ContextBinding("intent.context.orderId"));
    }

    @Test
    void outputFieldTheProducerDoesNotDeclareIsRejected() {
        List<CapabilityDescriptor> declaring = List.of(
                Fixtures.CAPABILITIES.get(0),
                withOutput(Fixtures.CAPABILITIES.get(1), "chargeId"),
                Fixtures.CAPABILITIES.get(2));

        assertThatThrownBy(() -> PlanParser.parse(Fixtures.VALID_PLAN, declaring, CONTEXT_NAMES))
                .isInstanceOf(InvalidPlanException.class)
                .hasMessageContaining("reads output 'id', which charge_card does not declare");
    }

    @Test
    void declaredOutputFieldIsAccepted() {
        List<CapabilityDescriptor> declaring = List.of(
                Fixtures.CAPABILITIES.get(0),
                withOutput(Fixtures.CAPABILITIES.get(1), "id"),
                Fixtures.CAPABILITIES.get(2));

        assertThat(PlanParser.parse(Fixtures.VALID_PLAN, declaring, CONTEXT_NAMES)
                        .getSteps())
                .hasSize(3);
    }

    @Test
    void unknownCapabilityIsRejected() {
        assertInvalid(Fixtures.VALID_PLAN.replace("\"send_receipt\"", "\"send_fax\""), "unknown capability 'send_fax'");
    }

    @Test
    void duplicateStepIdIsRejected() {
        assertInvalid(Fixtures.VALID_PLAN.replace("\"id\": \"receipt\"", "\"id\": \"charge\""), "used twice");
    }

    @Test
    void dependencyOnUnknownStepIsRejected() {
        assertInvalid(
                Fixtures.VALID_PLAN.replace("\"dependsOn\": [\"validate\"]", "\"dependsOn\": [\"ghost\"]"),
                "unknown step 'ghost'");
    }

    @Test
    void selfDependencyIsRejected() {
        assertInvalid(
                Fixtures.VALID_PLAN.replace("\"dependsOn\": [\"validate\"]", "\"dependsOn\": [\"charge\"]"),
                "depends on itself");
    }

    @Test
    void cycleIsRejected() {
        String cyclic = Fixtures.VALID_PLAN.replace(
                "\"id\": \"validate\", \"capabilityId\": \"validate_order\", \"dependsOn\": []",
                "\"id\": \"validate\", \"capabilityId\": \"validate_order\", \"dependsOn\": [\"receipt\"]");

        assertInvalid(cyclic, "cycle");
    }

    @Test
    void missingRequiredInputIsRejected() {
        String noAmount = """
                {"steps": [{"id": "charge", "capabilityId": "charge_card", "dependsOn": [],
                  "inputs": [{"name": "orderId", "source": "context", "value": "orderId", "stepId": null, "field": null}]}]}""";

        assertInvalid(noAmount, "required input 'amount'");
    }

    @Test
    void literalWithoutValueIsRejected() {
        assertInvalid(
                Fixtures.VALID_PLAN.replace("\"value\": \"42.00\"", "\"value\": null"), "literal without a value");
    }

    @Test
    void unknownSourceIsRejected() {
        assertInvalid(
                Fixtures.VALID_PLAN.replace("\"source\": \"literal\"", "\"source\": \"env\""), "unknown source 'env'");
    }

    @Test
    void emptyPlanIsRejected() {
        assertInvalid("{\"steps\": []}", "no steps");
    }

    @Test
    void nonJsonAnswerIsRejected() {
        assertInvalid("Sure! Here is your plan: validate, then charge.", "not plan JSON");
    }

    @Test
    void blankAnswerIsRejected() {
        assertInvalid("  ", "empty");
    }

    @Test
    void oversizedPlanIsRejected() {
        StringBuilder steps = new StringBuilder("{\"steps\": [");
        for (int i = 0; i <= PlanParser.MAX_STEPS; i++) {
            steps.append(i == 0 ? "" : ",")
                    .append("{\"id\": \"s")
                    .append(i)
                    .append("\", \"capabilityId\": \"send_receipt\", \"dependsOn\": [], \"inputs\": [")
                    .append(
                            "{\"name\": \"orderId\", \"source\": \"literal\", \"value\": \"1\", \"stepId\": null, \"field\": null}]}");
        }

        assertInvalid(steps.append("]}").toString(), "more than " + PlanParser.MAX_STEPS);
    }

    @Test
    void invalidStepIdIsRejected() {
        assertInvalid(Fixtures.VALID_PLAN.replace("\"id\": \"validate\"", "\"id\": \"has space\""), "step id");
    }

    private static CapabilityDescriptor withOutput(CapabilityDescriptor c, String output) {
        return new CapabilityDescriptor(
                c.id(),
                c.description(),
                c.inputParameters(),
                List.of(new ParameterDescriptor(output, "string", true, null)),
                c.idempotent(),
                c.async(),
                c.contract(),
                c.tenantIds());
    }

    private static void assertInvalid(String answer, String reason) {
        assertThatThrownBy(() -> PlanParser.parse(answer, Fixtures.CAPABILITIES, CONTEXT_NAMES))
                .isInstanceOf(InvalidPlanException.class)
                .hasMessageContaining(reason);
    }
}
