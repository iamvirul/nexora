package com.nexora.cli;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexora.api.NexoraEngine;
import com.nexora.core.execution.ExecutionResult;
import com.nexora.core.execution.ExecutionStatus;
import com.nexora.core.intent.Intent;
import com.nexora.event.StepCompletedEvent;
import com.nexora.event.StepFailedEvent;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

import java.util.Map;
import java.util.concurrent.Callable;

@Command(
        name = "run",
        description = "Execute an intent and print the result.",
        mixinStandardHelpOptions = true
)
public class RunCommand implements Callable<Integer> {

    private static final ObjectMapper JSON = new ObjectMapper();

    @ParentCommand
    private NexoraCli parent;

    @Option(names = {"-g", "--goal"}, required = true, description = "Intent goal string.")
    private String goal;

    @Option(names = {"-c", "--context"}, description = "Intent context as JSON object. Default: {}",
            defaultValue = "{}")
    private String contextJson;

    @Option(names = {"--no-events"}, description = "Suppress per-step event output.")
    private boolean noEvents;

    @Override
    public Integer call() throws Exception {
        Map<String, Object> context = JSON.readValue(contextJson, new TypeReference<>() {});

        NexoraEngine engine = parent.engine();

        if (!noEvents) {
            engine.subscribe(StepCompletedEvent.class, e ->
                    System.out.printf("  ✓ %-22s %dms%n", e.stepId(), e.elapsed().toMillis()));
            engine.subscribe(StepFailedEvent.class, e ->
                    System.out.printf("  ✗ %-22s [%s] %s%n", e.stepId(), e.failureCode(), e.failureMessage()));
        }

        System.out.println("Running: " + goal);
        System.out.println();

        ExecutionResult result = engine.execute(new Intent(goal, context)).get();

        System.out.println();
        System.out.printf("Status:      %s%n", result.status());
        System.out.printf("ExecutionId: %s%n", result.executionId());
        System.out.printf("Steps:       %d total, %d completed, %d failed%n",
                result.stepResults().size(),
                result.stepResults().stream().filter(sr -> sr.getStatus() == ExecutionStatus.COMPLETED).count(),
                result.stepResults().stream().filter(sr -> sr.getStatus() == ExecutionStatus.FAILED).count()
        );

        return result.status() == ExecutionStatus.COMPLETED ? 0 : 1;
    }
}
