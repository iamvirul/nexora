package me.virul.nexora.benchmarks;

import java.util.Map;
import java.util.concurrent.TimeUnit;
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
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

/**
 * One intent planned into one step and run to completion through {@link NexoraEngine#execute(Intent)}:
 * planning, scheduling, the interceptor pipeline and the result, with a capability that does nothing.
 */
@State(Scope.Benchmark)
@BenchmarkMode({Mode.Throughput, Mode.AverageTime})
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(1)
public class SingleStepExecutionBenchmark {

    static final String GOAL = "single";

    private NexoraEngine engine;
    private Intent intent;

    /** Builds the engine with one step matching {@value #GOAL}. */
    @Setup
    public void setUp() {
        engine = NexoraEngine.builder()
                .withPlugin(
                        new BenchmarkPlugin("bench-single", Map.of(Capabilities.NOOP, Capabilities.NOOP_CAPABILITY)))
                .withStepDefinition(new StepDefinition("step", Capabilities.NOOP, GOAL::equals))
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
