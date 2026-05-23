package com.nexora.cli;

import com.nexora.api.NexoraEngine;
import com.nexora.core.execution.ExecutionResult;
import com.nexora.core.execution.ExecutionStatus;
import com.nexora.event.PlanAmendedEvent;
import com.nexora.event.StepCompletedEvent;
import com.nexora.event.StepFailedEvent;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.ParameterException;

import java.util.Map;
import java.util.concurrent.Callable;

@Command(
        name = "demo",
        // description loaded from help/demo.help at startup via HelpLoader
        mixinStandardHelpOptions = true
)
public class DemoCommand implements Callable<Integer> {

    @Spec
    private CommandSpec spec;

    @Option(names = {"--order-id"}, defaultValue = "ORD-42",
            description = "Order ID to use in the demo. Default: ${DEFAULT-VALUE}")
    private String orderId;

    @Option(names = {"--timeout"},
            description = "Optional plan-level deadline timeout (in milliseconds) to trigger execution timeout")
    private Long timeoutMs;

    @Override
    public Integer call() throws Exception {
        System.out.println("Nexora Feature Demo");
        System.out.println("===================");
        System.out.println();

        System.out.println("Features demonstrated:");
        System.out.println("  1. Pluggable planner SPI  - rule-based planner wired via CompositePlanner");
        System.out.println("  2. Reactive plan amendment - validate_order injects audit_log at runtime");
        System.out.println("  3. Capability contracts    - charge_card declares p99 SLA + fallback");
        System.out.println("  4. Plan-level deadline     - cancels execution when deadline is exceeded");
        System.out.println();

        System.out.println("Initial DAG (from planner):");
        System.out.println("  validate_order --+");
        System.out.println("                   +--> charge_card --> send_receipt");
        System.out.println("  fetch_inventory -+");
        System.out.println();
        System.out.println("  validate_order will amend the plan at runtime, injecting:");
        System.out.println("  --> audit_log (runs after validate_order, before send_receipt)");
        System.out.println();

        NexoraEngine.Builder builder = NexoraEngine.builder();
        DemoCapabilities.wire(builder);
        NexoraEngine engine = builder.build();

        engine.subscribe(StepCompletedEvent.class, e ->
                System.out.printf("  ✓ %-22s  %dms%n", e.stepId(), e.elapsed().toMillis()));
        engine.subscribe(StepFailedEvent.class, e ->
                System.out.printf("  ✗ %-22s  [%s] %s%n", e.stepId(), e.failureCode(), e.failureMessage()));
        engine.subscribe(PlanAmendedEvent.class, e ->
                System.out.printf("  ~ plan amended: %-10s -> %s%n", e.amendmentType(), e.targetStepId()));

        System.out.println("Execution:");
        java.util.concurrent.CompletableFuture<ExecutionResult> future;
        if (timeoutMs != null) {
            if (timeoutMs <= 0) {
                throw new ParameterException(spec.commandLine(),
                        "Timeout specified via --timeout (timeoutMs=" + timeoutMs + ") must be greater than zero.");
            }
            future = engine.execute("process order payment notification", Map.of("orderId", orderId), java.time.Duration.ofMillis(timeoutMs));
        } else {
            future = engine.execute("process order payment notification", Map.of("orderId", orderId));
        }
        ExecutionResult result = future.get();

        System.out.println();
        System.out.printf("Result:   %s%n", result.status());
        System.out.printf("Steps:    %d executed%n", result.stepResults().size());
        System.out.printf("Trace:    %s%n", result.executionId());

        System.out.println();
        System.out.println("Contract health (charge_card):");
        NexoraEngine.HealthSnapshot health = NexoraEngine.HealthSnapshot.from(engine.capabilityHealth("charge_card"));
        System.out.printf("  samples=%d  error-rate=%.0f%%  p99=%dms%n",
                health.sampleCount(),
                health.errorRate() * 100,
                health.p99Latency().toMillis());

        return result.status() == ExecutionStatus.COMPLETED ? 0 : 1;
    }

}
