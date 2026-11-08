package com.nexora.samples.llmplanner;

import static org.assertj.core.api.Assertions.assertThat;

import com.nexora.core.execution.ExecutionStatus;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Runs the offline demo end to end against the packaged plugin JAR; checks what it prints. */
@Tag("integration")
class LlmPlannerDemoAppIT {

    @Test
    void offlineDemoShowsBothPlansSideBySide() throws Exception {
        ByteArrayOutputStream printed = new ByteArrayOutputStream();
        LlmPlannerDemoApp.Options options = new LlmPlannerDemoApp.Options(
                LlmPlannerDemoApp.DEFAULT_GOAL, Path.of(System.getProperty("nexora.test.pluginJar")), true);

        LlmPlannerDemoApp.Outcome outcome;
        try (OfflineModel model = OfflineModel.start()) {
            outcome = LlmPlannerDemoApp.run(options, new PrintStream(printed, true, StandardCharsets.UTF_8));
        }

        assertThat(outcome.ruleStatus()).isEqualTo(ExecutionStatus.COMPLETED);
        assertThat(outcome.ruleSteps())
                .extracting(LlmPlannerDemoApp.RanStep::capabilityId)
                .containsExactly("validate_order", "charge_card", "send_receipt");
        assertThat(outcome.llmStatus()).isEqualTo(ExecutionStatus.COMPLETED);
        assertThat(outcome.llmFellBack()).isFalse();
        assertThat(outcome.llmSteps())
                .extracting(LlmPlannerDemoApp.RanStep::capabilityId)
                .containsExactlyInAnyOrder("validate_order", "fraud_check", "charge_card", "send_receipt");
        assertThat(outcome.llmSteps())
                .filteredOn(step -> step.capabilityId().equals("send_receipt"))
                .singleElement()
                .satisfies(step -> assertThat(step.inputs())
                        .containsEntry("chargeId", "ch_1001")
                        .containsEntry("email", "ada@example.com")
                        .containsEntry("orderId", "ORD-42"));
        assertThat(outcome.ruleSteps())
                .allSatisfy(step -> assertThat(step.inputs()).containsEntry("orderId", "ORD-42"));
        assertThat(printed.toString(StandardCharsets.UTF_8))
                .contains("Rule-based planner", "LLM planner: COMPLETED", "fraud_check");
    }
}
