package com.nexora.planner.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.nexora.core.intent.Intent;
import com.nexora.core.plan.Plan;
import com.nexora.spi.PlanningDeclinedException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class LlmPlannerTest {

    private static final Intent INTENT = new Intent(Fixtures.GOAL, Map.of("orderId", "ORD-1", "email", "a@b.c"));

    private final ListAppender<ILoggingEvent> plannerLog = new ListAppender<>();
    private final Logger plannerLogger = (Logger) LoggerFactory.getLogger(LlmPlanner.class);
    private final ScriptedClient client = new ScriptedClient();
    private final LlmPlanner planner =
            new LlmPlanner(client, Fixtures.config(LlmProvider.ANTHROPIC, "claude-opus-5-5", null));

    @BeforeEach
    void captureLog() {
        plannerLog.start();
        plannerLogger.addAppender(plannerLog);
    }

    @AfterEach
    void releaseLog() {
        plannerLogger.detachAppender(plannerLog);
    }

    @Test
    void validAnswerProducesThePlan() {
        client.answers.add(Fixtures.VALID_PLAN);

        Plan plan = planner.plan(INTENT, Fixtures.context());

        assertThat(plan.getSteps()).extracting(s -> s.id()).containsExactlyInAnyOrder("validate", "charge", "receipt");
    }

    @Test
    void promptCarriesGoalCapabilitiesAndContextNamesButNotContextValues() {
        client.answers.add(Fixtures.VALID_PLAN);

        planner.plan(INTENT, Fixtures.context());

        String user = client.lastPrompt.user();
        assertThat(user)
                .contains(Fixtures.GOAL, "charge_card", "orderId", "email")
                .doesNotContain("ORD-1", "a@b.c");
        assertThat(client.lastPrompt.system()).isEqualTo(PlanPromptBuilder.SYSTEM_PROMPT);
        assertThat(client.lastPrompt.schema()).isEqualTo(PlanSchema.schema());
    }

    @Test
    void invalidAnswerDeclinesAndLogsAWarning() {
        client.answers.add(
                "{\"steps\": [{\"id\": \"x\", \"capabilityId\": \"launch_rocket\", \"dependsOn\": [], \"inputs\": []}]}");

        assertThatThrownBy(() -> planner.plan(INTENT, Fixtures.context()))
                .isInstanceOf(PlanningDeclinedException.class)
                .hasMessageContaining("INVALID_PLAN");

        assertThat(plannerLog.list).anySatisfy(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage()).contains("reason=INVALID_PLAN", "launch_rocket");
        });
        assertThat(planner.stats().declined()).isEqualTo(1);
    }

    @Test
    void declineDetailIsOneBoundedLine() {
        client.answers.add(
                "{\"steps\": [{\"id\": \"x\", \"capabilityId\": \"evil\\nFAKE LOG LINE\", \"dependsOn\": [], \"inputs\": []}]}");

        assertThatThrownBy(() -> planner.plan(INTENT, Fixtures.context()))
                .isInstanceOf(PlanningDeclinedException.class);

        assertThat(plannerLog.list)
                .allSatisfy(e -> assertThat(e.getFormattedMessage()).doesNotContain("\n"));
        assertThat(LlmPlanner.oneLine("a".repeat(1_000))).hasSizeLessThan(400);
    }

    @Test
    void failedCallDeclinesWithTheCallReason() {
        client.failure = new LlmCallException(LlmCallException.Reason.UNAVAILABLE, "HTTP 529 overloaded");

        assertThatThrownBy(() -> planner.plan(INTENT, Fixtures.context()))
                .isInstanceOf(PlanningDeclinedException.class)
                .hasMessageContaining("UNAVAILABLE");
        assertThat(plannerLog.list).anySatisfy(e -> assertThat(e.getLevel()).isEqualTo(Level.WARN));
    }

    @Test
    void unexpectedClientExceptionStillDeclines() {
        client.unexpected = new IllegalStateException("sdk bug");

        assertThatThrownBy(() -> planner.plan(INTENT, Fixtures.context()))
                .isInstanceOf(PlanningDeclinedException.class)
                .hasMessageContaining("CLIENT_ERROR");
    }

    @Test
    void sameGoalAndCapabilitiesHitTheCacheOnTheSecondCall() {
        client.answers.add(Fixtures.VALID_PLAN);

        Plan first = planner.plan(INTENT, Fixtures.context());
        Plan second = planner.plan(
                new Intent(Fixtures.GOAL, Map.of("orderId", "ORD-2", "email", "x@y.z")), Fixtures.context());

        assertThat(second).isSameAs(first);
        assertThat(client.calls.get()).isEqualTo(1);
        assertThat(planner.stats()).isEqualTo(new LlmPlanner.Stats(1, 1, 0));
    }

    @Test
    void differentCapabilitiesMissTheCache() {
        client.answers.add(Fixtures.VALID_PLAN);
        client.answers.add(Fixtures.VALID_PLAN);
        planner.plan(INTENT, Fixtures.context());

        var fewer = Fixtures.CAPABILITIES;
        planner.plan(INTENT, Fixtures.context(List.of(fewer.get(0), fewer.get(1), fewer.get(2), extraCapability())));

        assertThat(client.calls.get()).isEqualTo(2);
    }

    @Test
    void invalidAnswerIsNotCached() {
        client.answers.add("not json");
        client.answers.add(Fixtures.VALID_PLAN);
        assertThatThrownBy(() -> planner.plan(INTENT, Fixtures.context()))
                .isInstanceOf(PlanningDeclinedException.class);

        Plan plan = planner.plan(INTENT, Fixtures.context());

        assertThat(plan.getSteps()).hasSize(3);
        assertThat(client.calls.get()).isEqualTo(2);
    }

    @Test
    void cannotPlanWithoutCapabilities() {
        assertThat(planner.canPlan(INTENT, Fixtures.context(List.of()))).isFalse();
        assertThat(planner.canPlan(INTENT, Fixtures.context())).isTrue();
    }

    @Test
    void descriptorUsesConfiguredPriority() {
        assertThat(planner.descriptor().id()).isEqualTo(LlmPlanner.PLANNER_ID);
        assertThat(planner.descriptor().priority()).isEqualTo(LlmPlannerConfig.DEFAULT_PRIORITY);
    }

    private static com.nexora.spi.CapabilityDescriptor extraCapability() {
        return new com.nexora.spi.CapabilityDescriptor("refund", "Refunds", List.of(), List.of(), true, false);
    }

    /** Answers from a queue, or throws {@link #failure}; records the last prompt. */
    private static final class ScriptedClient implements PlanCompletionClient {
        final Deque<String> answers = new ArrayDeque<>();
        final AtomicInteger calls = new AtomicInteger();
        volatile PlanPrompt lastPrompt;
        volatile LlmCallException failure;
        volatile RuntimeException unexpected;

        @Override
        public String complete(PlanPrompt prompt) {
            calls.incrementAndGet();
            lastPrompt = prompt;
            if (failure != null) {
                throw failure;
            }
            if (unexpected != null) {
                throw unexpected;
            }
            return answers.poll();
        }

        @Override
        public void close() {}
    }
}
