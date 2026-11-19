package me.virul.nexora.benchmarks;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Collectors;
import me.virul.nexora.benchmarks.ContractMonitorBenchmark.Contract;
import me.virul.nexora.benchmarks.PlanAmendmentBenchmark.Amendment;
import me.virul.nexora.core.capability.ResultStatus;
import me.virul.nexora.core.execution.ExecutionResult;
import me.virul.nexora.core.execution.ExecutionStatus;
import me.virul.nexora.core.execution.StepResult;
import me.virul.nexora.event.InProcessEventBus;
import me.virul.nexora.loader.PluginManager;
import me.virul.nexora.registry.DefaultCapabilityRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

class BenchmarkPathsTest {

    @Test
    void singleStepBenchmarkCompletesItsOneStep() {
        SingleStepExecutionBenchmark benchmark = new SingleStepExecutionBenchmark();
        benchmark.setUp();
        try {
            ExecutionResult result = benchmark.execute();

            assertThat(result.status()).isEqualTo(ExecutionStatus.COMPLETED);
            assertThat(result.stepResults()).extracting(StepResult::stepId).containsExactly("step");
        } finally {
            benchmark.tearDown();
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {4, 8})
    void dagBenchmarkRunsEveryParallelStep(int stepCount) {
        DagExecutionBenchmark benchmark = new DagExecutionBenchmark();
        benchmark.stepCount = stepCount;
        benchmark.setUp();
        try {
            ExecutionResult result = benchmark.execute();

            assertThat(result.status()).isEqualTo(ExecutionStatus.COMPLETED);
            assertThat(result.stepResults()).hasSize(stepCount).allMatch(StepResult::succeeded);
        } finally {
            benchmark.tearDown();
        }
    }

    @ParameterizedTest
    @EnumSource(Amendment.class)
    void amendmentBenchmarkAppliesItsAmendment(Amendment amendment) {
        PlanAmendmentBenchmark benchmark = new PlanAmendmentBenchmark();
        benchmark.amendment = amendment;
        benchmark.setUp();
        try {
            ExecutionResult result = benchmark.execute();

            assertThat(result.status()).isEqualTo(ExecutionStatus.COMPLETED);
            assertThat(statusByStep(result)).isEqualTo(expectedSteps(amendment));
        } finally {
            benchmark.tearDown();
        }
    }

    @ParameterizedTest
    @EnumSource(Contract.class)
    void contractMonitorBenchmarkCallsTheCapability(Contract contract) {
        ContractMonitorBenchmark benchmark = new ContractMonitorBenchmark();
        benchmark.contract = contract;
        benchmark.setUp();

        assertThat(benchmark.direct().succeeded()).isTrue();
        assertThat(benchmark.invoke().succeeded()).isTrue();
        assertThat(benchmark.invokeContended().succeeded()).isTrue();
    }

    @Test
    void pluginJarRegistersItsCapabilityWhenActivated(@TempDir Path directory) throws IOException {
        Path jar = PluginLoadBenchmark.writePluginJar(directory.resolve("plugin.jar"));
        DefaultCapabilityRegistry registry = new DefaultCapabilityRegistry();
        PluginManager manager = new PluginManager(registry, new InProcessEventBus(Runnable::run));

        String pluginId = manager.loadPlugin(jar);
        manager.activatePlugin(pluginId);

        assertThat(pluginId).isEqualTo(JarPlugin.PLUGIN_ID);
        assertThat(registry.find(JarPlugin.CAPABILITY_ID)).isPresent();
        manager.deactivatePlugin(pluginId);
    }

    @Test
    void pluginLoadBenchmarkCanRepeatAndLeavesNothingRegistered() throws IOException {
        PluginLoadBenchmark benchmark = new PluginLoadBenchmark();
        benchmark.setUp();
        try {
            assertThat(benchmark.loadActivateDeactivate()).isEqualTo(JarPlugin.PLUGIN_ID);
            assertThat(benchmark.loadActivateDeactivate()).isEqualTo(JarPlugin.PLUGIN_ID);

            assertThat(benchmark.registry().find(JarPlugin.CAPABILITY_ID)).isEmpty();
        } finally {
            benchmark.tearDown();
        }
    }

    private static Map<String, ResultStatus> statusByStep(ExecutionResult result) {
        return result.stepResults().stream()
                .collect(Collectors.toMap(
                        StepResult::stepId, step -> step.capabilityResult().status()));
    }

    private static Map<String, ResultStatus> expectedSteps(Amendment amendment) {
        String source = PlanAmendmentBenchmark.SOURCE;
        String target = PlanAmendmentBenchmark.TARGET;
        return switch (amendment) {
            case NONE, MODIFY_INPUT -> Map.of(source, ResultStatus.SUCCESS, target, ResultStatus.SUCCESS);
            case SKIP_STEP -> Map.of(source, ResultStatus.SUCCESS, target, ResultStatus.SKIPPED);
            case ADD_STEP ->
                Map.of(
                        source,
                        ResultStatus.SUCCESS,
                        target,
                        ResultStatus.SUCCESS,
                        PlanAmendmentBenchmark.ADDED,
                        ResultStatus.SUCCESS);
        };
    }
}
