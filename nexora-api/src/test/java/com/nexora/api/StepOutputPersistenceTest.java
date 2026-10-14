package com.nexora.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.nexora.api.observability.NexoraObservability;
import com.nexora.api.observability.NexoraObservability.StepSnapshot;
import com.nexora.core.capability.CapabilityResult;
import com.nexora.core.execution.ExecutionResult;
import com.nexora.core.execution.ExecutionStatus;
import com.nexora.core.intent.Intent;
import com.nexora.core.plan.InputBinding;
import com.nexora.persistence.StepOutput;
import com.nexora.persistence.StepRecord;
import com.nexora.persistence.StepState;
import com.nexora.persistence.jdbc.JdbcExecutionStore;
import com.nexora.planner.model.StepDefinition;
import com.nexora.runtime.engine.ExecutionEngine;
import com.nexora.spi.Capability;
import com.nexora.spi.CapabilityDescriptor;
import com.nexora.spi.CapabilityProvider;
import com.nexora.spi.NexoraPlugin;
import com.nexora.spi.PluginContext;
import com.nexora.spi.PluginDescriptor;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class StepOutputPersistenceTest {

    private static final long WAIT_SECONDS = 5;
    private static final String GOAL = "process order";

    private final AtomicReference<Object> chargeOutput = new AtomicReference<>();
    private final AtomicReference<Object> receiptInput = new AtomicReference<>();
    private final ListAppender<ILoggingEvent> engineLog = new ListAppender<>();
    private final Logger engineLogger = (Logger) LoggerFactory.getLogger(ExecutionEngine.class);

    private JdbcExecutionStore store;
    private NexoraEngine engine;

    @BeforeEach
    void setUp() {
        store = JdbcExecutionStore.h2InMemory();
        engineLog.start();
        engineLogger.addAppender(engineLog);
    }

    @AfterEach
    void tearDown() {
        engineLogger.detachAppender(engineLog);
        if (engine != null) {
            engine.close();
        }
        store.close();
    }

    @Test
    void completedStepOutputIsReadableThroughFindById() throws Exception {
        chargeOutput.set(Map.of("paymentId", "p-1", "amount", 42, "at", Instant.parse("2026-10-14T10:00:00Z")));
        engine = builder().build();

        ExecutionResult result = run();

        StepRecord charge = storedStep(result.executionId(), "charge");
        assertThat(charge.output()).isEqualTo(Map.of("paymentId", "p-1", "amount", 42, "at", "2026-10-14T10:00:00Z"));
        assertThat(charge.outputTruncated()).isFalse();
        assertThat(storedStep(result.executionId(), "receipt").output())
                .isEqualTo(Map.of(StepOutput.VALUE_KEY, "sent"));
    }

    @Test
    void oversizedOutputIsTruncatedLoggedAndStillPassedWholeToTheNextStep() throws Exception {
        String blob = "x".repeat(10_000);
        chargeOutput.set(Map.of("blob", blob));
        engine = builder().withStepOutputMaxBytes(1024).build();

        ExecutionResult result = run();

        assertThat(result.status()).isEqualTo(ExecutionStatus.COMPLETED);
        StepRecord charge = storedStep(result.executionId(), "charge");
        assertThat(charge.outputTruncated()).isTrue();
        assertThat((String) charge.output().get(StepOutput.PREVIEW_KEY)).startsWith("{\"blob\":\"xxx");
        assertThat(receiptInput.get()).isEqualTo(Map.of("blob", blob));
        assertThat(warnings())
                .anySatisfy(message -> assertThat(message)
                        .contains("truncated", "executionId=" + result.executionId(), "stepId=charge", "maxBytes=1024")
                        .doesNotContain(blob));
    }

    @Test
    void outputThatCannotBeStoredLeavesTheStepCompletedWithNoOutput() throws Exception {
        Map<String, Object> cyclic = new HashMap<>();
        cyclic.put("self", cyclic);
        chargeOutput.set(cyclic);
        engine = builder().build();

        ExecutionResult result = run();

        assertThat(result.status()).isEqualTo(ExecutionStatus.COMPLETED);
        StepRecord charge = storedStep(result.executionId(), "charge");
        assertThat(charge.state()).isEqualTo(StepState.COMPLETED);
        assertThat(charge.output()).isEmpty();
        assertThat(warnings())
                .anySatisfy(message ->
                        assertThat(message).contains("step output not stored", "stepId=charge", "java.util.HashMap"));
    }

    @Test
    void stepThatReturnsNoOutputHasAnEmptyOutput() throws Exception {
        chargeOutput.set(null);
        engine = builder().build();

        ExecutionResult result = run();

        assertThat(result.status()).isEqualTo(ExecutionStatus.COMPLETED);
        StepRecord charge = storedStep(result.executionId(), "charge");
        assertThat(charge.state()).isEqualTo(StepState.COMPLETED);
        assertThat(charge.output()).isEmpty();
        assertThat(charge.outputTruncated()).isFalse();
    }

    @Test
    void zeroCapStoresNoOutputs() throws Exception {
        chargeOutput.set(Map.of("cardToken", "tok_secret"));
        engine = builder().withStepOutputMaxBytes(0).build();

        ExecutionResult result = run();

        assertThat(storedStep(result.executionId(), "charge").output()).isEmpty();
        assertThat(storedStep(result.executionId(), "receipt").output()).isEmpty();
    }

    @Test
    void capBetweenZeroAndTheMinimumIsRejected() {
        assertThatThrownBy(() -> NexoraEngine.builder().withStepOutputMaxBytes(StepOutput.MIN_MAX_BYTES - 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("stepOutputMaxBytes");
    }

    @Test
    void processSnapshotIncludesCompletedStepOutputs() throws Exception {
        chargeOutput.set(Map.of("paymentId", "p-1"));
        engine = builder().build();
        try (NexoraObservability observability = NexoraObservability.attach(engine)) {
            ExecutionResult result = run();

            StepSnapshot charge = await(() -> observability.processSnapshot().executions().stream()
                    .filter(e -> e.executionId().equals(result.executionId()))
                    .flatMap(e -> e.steps().stream())
                    .filter(s -> s.stepId().equals("charge") && !s.output().isEmpty())
                    .findFirst());

            assertThat(charge.output()).isEqualTo(Map.of("paymentId", "p-1"));
            assertThat(charge.outputTruncated()).isFalse();
        }
    }

    @Test
    void processSnapshotKeepsOnlyAPreviewOfLargeOutputs() throws Exception {
        chargeOutput.set(Map.of("blob", "y".repeat(20_000)));
        engine = builder().build();
        try (NexoraObservability observability = NexoraObservability.attach(engine)) {
            ExecutionResult result = run();

            StepSnapshot charge = await(() -> observability.processSnapshot().executions().stream()
                    .filter(e -> e.executionId().equals(result.executionId()))
                    .flatMap(e -> e.steps().stream())
                    .filter(s -> s.stepId().equals("charge") && s.outputTruncated())
                    .findFirst());

            assertThat((String) charge.output().get(StepOutput.PREVIEW_KEY)).hasSizeLessThan(4 * 1024);
            // The store keeps it whole: 20 KB is under its 64 KB default.
            assertThat(storedStep(result.executionId(), "charge").outputTruncated())
                    .isFalse();
        }
    }

    private NexoraEngine.Builder builder() {
        return NexoraEngine.builder()
                .withExecutionStore(store)
                .withPlugin(plugin())
                .withStepDefinition(StepDefinition.builder("charge", "charge")
                        .withMatcher(goal -> goal.contains("order"))
                        .build())
                .withStepDefinition(StepDefinition.builder("receipt", "receipt")
                        .withMatcher(goal -> goal.contains("order"))
                        .withInput("charge", InputBinding.fromStep("charge"))
                        .dependsOn("charge")
                        .build());
    }

    private ExecutionResult run() throws Exception {
        return engine.execute(new Intent(GOAL, Map.of("orderId", "ORD-1"))).get(WAIT_SECONDS, TimeUnit.SECONDS);
    }

    /** Step rows are written synchronously, before the result completes. */
    private StepRecord storedStep(String executionId, String stepId) {
        return store.findById(executionId).orElseThrow().steps().stream()
                .filter(s -> s.stepId().equals(stepId))
                .findFirst()
                .orElseThrow();
    }

    private List<String> warnings() {
        return engineLog.list.stream()
                .filter(e -> e.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    private static <T> T await(Supplier<Optional<T>> probe) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(WAIT_SECONDS).toNanos();
        while (System.nanoTime() < deadline) {
            Optional<T> value = probe.get();
            if (value.isPresent()) {
                return value.get();
            }
            Thread.sleep(10);
        }
        throw new AssertionError("Condition not met within " + WAIT_SECONDS + "s");
    }

    private NexoraPlugin plugin() {
        return new NexoraPlugin() {
            @Override
            public PluginDescriptor descriptor() {
                return new PluginDescriptor("step-output-test", "1.0", "step output test plugin", List.of(), null);
            }

            @Override
            public void initialize(PluginContext ctx) {}

            @Override
            public List<CapabilityProvider> capabilityProviders() {
                return List.of(
                        provider("charge", req -> CapabilityResult.success(chargeOutput.get())),
                        provider("receipt", req -> {
                            receiptInput.set(req.inputs().get("charge"));
                            return CapabilityResult.success("sent");
                        }));
            }

            @Override
            public void shutdown() {}
        };
    }

    private static CapabilityProvider provider(String id, Capability capability) {
        return new CapabilityProvider() {
            @Override
            public CapabilityDescriptor descriptor() {
                return new CapabilityDescriptor(id, id, List.of(), List.of(), true, false);
            }

            @Override
            public Capability create(PluginContext ctx) {
                return capability;
            }
        };
    }
}
