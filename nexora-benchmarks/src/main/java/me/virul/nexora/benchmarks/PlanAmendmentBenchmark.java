package me.virul.nexora.benchmarks;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import me.virul.nexora.api.NexoraEngine;
import me.virul.nexora.core.capability.CapabilityResult;
import me.virul.nexora.core.execution.ExecutionResult;
import me.virul.nexora.core.intent.Intent;
import me.virul.nexora.core.plan.AddStepAmendment;
import me.virul.nexora.core.plan.InputBinding;
import me.virul.nexora.core.plan.ModifyInputAmendment;
import me.virul.nexora.core.plan.PlanAmendment;
import me.virul.nexora.core.plan.SkipStepAmendment;
import me.virul.nexora.core.plan.Step;
import me.virul.nexora.planner.model.StepDefinition;
import me.virul.nexora.spi.Capability;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

/**
 * A two-step plan, {@value #SOURCE} then {@value #TARGET}, where {@value #SOURCE} returns one plan
 * amendment of the kind in {@link #amendment}. {@link Amendment#NONE} is the baseline: the
 * difference from it is the cost of applying the amendment, including its effect on the plan (a skip
 * saves running {@value #TARGET}, an added step runs one more step).
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(1)
public class PlanAmendmentBenchmark {

    static final String GOAL = "amend";
    static final String SOURCE = "source";
    static final String TARGET = "target";
    static final String ADDED = "added";
    static final String INPUT = "value";

    private static final String AMENDING = "bench.amend";

    /** The amendment {@value #SOURCE} returns. */
    public enum Amendment {
        /** No amendment, the baseline. */
        NONE,
        /** Overrides an input of {@value #TARGET}. */
        MODIFY_INPUT,
        /** Skips {@value #TARGET}. */
        SKIP_STEP,
        /** Adds a step that depends on {@value #SOURCE}. */
        ADD_STEP;

        List<PlanAmendment> amendments() {
            return switch (this) {
                case NONE -> List.of();
                case MODIFY_INPUT -> List.of(new ModifyInputAmendment(TARGET, INPUT, "amended"));
                case SKIP_STEP -> List.of(new SkipStepAmendment(TARGET));
                case ADD_STEP ->
                    List.of(new AddStepAmendment(new Step(
                            ADDED, Capabilities.NOOP, Map.of(), null, Set.of(SOURCE), null, null, null, null)));
            };
        }
    }

    /** The amendment under test. */
    @Param
    public Amendment amendment;

    private NexoraEngine engine;
    private Intent intent;

    /** Builds the engine with the two-step plan. */
    @Setup
    public void setUp() {
        CapabilityResult result = CapabilityResult.success(Map.of("ok", true), amendment.amendments());
        Capability amending = request -> result;
        engine = NexoraEngine.builder()
                .withPlugin(new BenchmarkPlugin(
                        "bench-amend", Map.of(AMENDING, amending, Capabilities.NOOP, Capabilities.NOOP_CAPABILITY)))
                .withStepDefinition(new StepDefinition(SOURCE, AMENDING, GOAL::equals))
                .withStepDefinition(new StepDefinition(
                        TARGET,
                        Capabilities.NOOP,
                        GOAL::equals,
                        Map.of(INPUT, InputBinding.literal("original")),
                        null,
                        Set.of(SOURCE),
                        null,
                        null))
                .withShutdownHook(false)
                .build();
        intent = new Intent(GOAL, Map.of());
    }

    /**
     * Runs one execution and waits for it.
     *
     * @return the result, consumed by JMH
     */
    @Benchmark
    public ExecutionResult execute() {
        return engine.execute(intent).join();
    }

    /** Shuts the engine down. */
    @TearDown
    public void tearDown() {
        engine.close();
    }
}
