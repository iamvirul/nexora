package com.nexora.api;

import com.nexora.core.capability.CapabilityResult;
import com.nexora.core.execution.ExecutionResult;
import com.nexora.core.execution.ExecutionStatus;
import com.nexora.core.intent.Intent;
import com.nexora.event.ExecutionDeadLetteredEvent;
import com.nexora.persistence.DeadLetterRecord;
import com.nexora.persistence.DeadLetterReviewState;
import com.nexora.persistence.jdbc.JdbcExecutionStore;
import com.nexora.planner.model.StepDefinition;
import com.nexora.spi.Capability;
import com.nexora.spi.CapabilityDescriptor;
import com.nexora.spi.CapabilityProvider;
import com.nexora.spi.NexoraPlugin;
import com.nexora.spi.PluginContext;
import com.nexora.spi.PluginDescriptor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class DeadLetterIntegrationTest {

    private JdbcExecutionStore store;
    private NexoraEngine engine;

    @BeforeEach
    void setUp() {
        store = JdbcExecutionStore.h2InMemory();
        engine = NexoraEngine.builder()
                .withExecutionStore(store)
                .withPlugin(failingPlugin())
                .withStepDefinition(StepDefinition.builder("fail_step", "always_fail")
                        .withMatcher(goal -> goal.contains("fail"))
                        .build())
                .build();
    }

    @AfterEach
    void tearDown() {
        store.close();
    }

    @Test
    void failedExecutionCreatesExactlyOneDeadLetter() throws Exception {
        ExecutionResult result = engine.execute(new Intent("fail this goal", Map.of())).get();

        assertEquals(ExecutionStatus.FAILED, result.status());

        List<DeadLetterRecord> dls = store.findDeadLetters(DeadLetterReviewState.PENDING, 0, 10);
        assertEquals(1, dls.size(), "Exactly one dead letter must be created");
        DeadLetterRecord dl = dls.get(0);
        assertEquals(result.executionId(), dl.executionId());
        assertEquals("fail this goal", dl.goal());
        assertEquals("STEP_FAILED", dl.failureCode());
        assertEquals(DeadLetterReviewState.PENDING, dl.reviewState());
    }

    @Test
    void deadLetteredEventFiresWithCorrectDetails() throws Exception {
        List<ExecutionDeadLetteredEvent> events = new CopyOnWriteArrayList<>();
        engine.subscribe(ExecutionDeadLetteredEvent.class, events::add);

        ExecutionResult result = engine.execute(new Intent("fail this goal", Map.of())).get();
        // give the event bus a moment to deliver async
        Thread.sleep(200);

        assertEquals(1, events.size());
        ExecutionDeadLetteredEvent evt = events.get(0);
        assertEquals(result.executionId(), evt.executionId());
        assertEquals("STEP_FAILED", evt.failureCode());
        assertNotNull(evt.deadLetterId());
    }

    @Test
    void successfulExecutionDoesNotCreateDeadLetter() throws Exception {
        JdbcExecutionStore localStore = JdbcExecutionStore.h2InMemory();
        NexoraEngine successEngine = NexoraEngine.builder()
                .withExecutionStore(localStore)
                .withPlugin(successPlugin())
                .withStepDefinition(StepDefinition.builder("pass_step", "always_pass")
                        .withMatcher(goal -> goal.contains("pass"))
                        .build())
                .build();

        ExecutionResult result = successEngine.execute(new Intent("pass this goal", Map.of())).get();

        assertEquals(ExecutionStatus.COMPLETED, result.status());
        List<DeadLetterRecord> dls = localStore.findDeadLetters(null, 0, 10);
        assertTrue(dls.isEmpty(), "No dead letters should be created for successful executions");
        localStore.close();
    }

    @Test
    void resolveDeadLetterTransitionsState() throws Exception {
        engine.execute(new Intent("fail this goal", Map.of())).get();

        List<DeadLetterRecord> pending = store.findDeadLetters(DeadLetterReviewState.PENDING, 0, 10);
        assertEquals(1, pending.size());
        String dlId = pending.get(0).id();

        store.updateDeadLetterState(dlId, DeadLetterReviewState.RESOLVED, "no action needed");

        DeadLetterRecord resolved = store.findDeadLetterById(dlId).orElseThrow();
        assertEquals(DeadLetterReviewState.RESOLVED, resolved.reviewState());
        assertEquals("no action needed", resolved.resolveReason());
        assertTrue(store.findDeadLetters(DeadLetterReviewState.PENDING, 0, 10).isEmpty());
    }

    private NexoraPlugin failingPlugin() {
        return new NexoraPlugin() {
            @Override public PluginDescriptor descriptor() {
                return new PluginDescriptor("test-failing", "1.0", "test", List.of(), null);
            }
            @Override public void initialize(PluginContext ctx) {}
            @Override public List<CapabilityProvider> capabilityProviders() {
                return List.of(new CapabilityProvider() {
                    @Override public CapabilityDescriptor descriptor() {
                        return new CapabilityDescriptor("always_fail", "always_fail", List.of(), List.of(), true, false);
                    }
                    @Override public Capability create(PluginContext ctx) {
                        return req -> CapabilityResult.failure("TEST_FAIL", "Intentional test failure");
                    }
                });
            }
            @Override public void shutdown() {}
        };
    }

    private NexoraPlugin successPlugin() {
        return new NexoraPlugin() {
            @Override public PluginDescriptor descriptor() {
                return new PluginDescriptor("test-success", "1.0", "test", List.of(), null);
            }
            @Override public void initialize(PluginContext ctx) {}
            @Override public List<CapabilityProvider> capabilityProviders() {
                return List.of(new CapabilityProvider() {
                    @Override public CapabilityDescriptor descriptor() {
                        return new CapabilityDescriptor("always_pass", "always_pass", List.of(), List.of(), true, false);
                    }
                    @Override public Capability create(PluginContext ctx) {
                        return req -> CapabilityResult.success("ok");
                    }
                });
            }
            @Override public void shutdown() {}
        };
    }
}
