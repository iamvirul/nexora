package me.virul.nexora.benchmarks;

import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import me.virul.nexora.api.NexoraEngine;
import me.virul.nexora.core.execution.ExecutionResult;
import me.virul.nexora.core.intent.Intent;
import me.virul.nexora.planner.model.StepDefinition;
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
 * A plan of {@link #stepCount} independent steps, which the scheduler starts in parallel, run to
 * completion. The capabilities do nothing, so this is the cost of fanning out and joining the DAG.
 */
@State(Scope.Benchmark)
@BenchmarkMode({Mode.Throughput, Mode.AverageTime})
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(1)
public class DagExecutionBenchmark {

    static final String GOAL = "dag";

    /** The number of parallel steps in the plan. */
    @Param({"4", "8"})
    public int stepCount;

    private NexoraEngine engine;
    private Intent intent;

    /** Builds the engine with {@link #stepCount} independent steps matching {@value #GOAL}. */
    @Setup
    public void setUp() {
        NexoraEngine.Builder builder = NexoraEngine.builder()
                .withPlugin(new BenchmarkPlugin("bench-dag", Map.of(Capabilities.NOOP, Capabilities.NOOP_CAPABILITY)))
                .withShutdownHook(false);
        IntStream.range(0, stepCount)
                .mapToObj(i -> new StepDefinition("step-" + i, Capabilities.NOOP, GOAL::equals))
                .forEach(builder::withStepDefinition);
        engine = builder.build();
        intent = new Intent(GOAL, Map.of());
    }

    /**
     * Runs one execution and waits for every step.
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
