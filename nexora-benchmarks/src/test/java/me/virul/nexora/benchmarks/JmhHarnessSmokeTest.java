package me.virul.nexora.benchmarks;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Collection;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.openjdk.jmh.results.RunResult;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.openjdk.jmh.runner.options.TimeValue;

class JmhHarnessSmokeTest {

    private static final Set<String> BENCHMARKS = Set.of(
            "SingleStepExecutionBenchmark.execute",
            "DagExecutionBenchmark.execute",
            "PlanAmendmentBenchmark.execute",
            "ContractMonitorBenchmark.direct",
            "ContractMonitorBenchmark.invoke",
            "ContractMonitorBenchmark.invokeContended",
            "PluginLoadBenchmark.loadActivateDeactivate");

    @Test
    void everyBenchmarkRunsThroughTheJmhHarness() throws RunnerException {
        Options options = new OptionsBuilder()
                .include(getClass().getPackageName() + ".*Benchmark.*")
                .forks(0)
                .warmupIterations(0)
                .measurementIterations(1)
                .measurementTime(TimeValue.milliseconds(100))
                .timeout(TimeValue.seconds(30))
                .timeUnit(TimeUnit.MICROSECONDS)
                .shouldFailOnError(true)
                .build();

        Collection<RunResult> results = new Runner(options).run();

        Set<String> ran = results.stream()
                .map(result -> shortName(result.getParams().getBenchmark()))
                .collect(Collectors.toSet());
        assertThat(ran).isEqualTo(BENCHMARKS);
        assertThat(results)
                .allSatisfy(result ->
                        assertThat(result.getPrimaryResult().getScore()).isPositive());
    }

    private static String shortName(String benchmark) {
        String[] parts = benchmark.split("\\.");
        return parts[parts.length - 2] + "." + parts[parts.length - 1];
    }
}
