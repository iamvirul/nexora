package com.nexora.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexora.api.NexoraEngine;
import com.nexora.core.intent.Intent;
import com.nexora.persistence.DeadLetterRecord;
import com.nexora.persistence.DeadLetterReviewState;
import com.nexora.persistence.ExecutionStore;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

import java.util.List;
import java.util.concurrent.Callable;

@Command(
        name = "dlq",
        mixinStandardHelpOptions = true,
        description = "Manage the dead letter queue.",
        subcommands = {
                DlqCommand.ListCommand.class,
                DlqCommand.ReplayCommand.class,
                DlqCommand.ResolveCommand.class
        }
)
public class DlqCommand implements Callable<Integer> {

    @ParentCommand
    NexoraCli parent;

    @Override
    public Integer call() {
        System.out.println("Usage: nexora dlq <list|replay|resolve>");
        return 0;
    }

    private static ExecutionStore requireStore(NexoraEngine engine) {
        ExecutionStore store = engine.getStore();
        if (store == null) {
            System.err.println("Error: persistence store is not configured.");
            return null;
        }
        return store;
    }

    @Command(name = "list", mixinStandardHelpOptions = true,
             description = "List dead letter queue entries.")
    static class ListCommand implements Callable<Integer> {

        @ParentCommand
        DlqCommand dlq;

        @Option(names = {"--state"}, defaultValue = "PENDING",
                description = "Filter by review state: PENDING, RESOLVED, REPLAYED, ALL. Default: ${DEFAULT-VALUE}")
        private String state;

        @Option(names = {"--page"}, defaultValue = "0")
        private int page;

        @Option(names = {"--size"}, defaultValue = "20")
        private int size;

        @Override
        public Integer call() {
            ExecutionStore store = requireStore(dlq.parent.engine());
            if (store == null) return 1;

            DeadLetterReviewState filter;
            if ("ALL".equalsIgnoreCase(state)) {
                filter = null;
            } else {
                try {
                    filter = DeadLetterReviewState.valueOf(state.toUpperCase());
                } catch (IllegalArgumentException e) {
                    System.err.printf("Invalid state '%s'. Allowed values: ALL, %s%n",
                            state, java.util.Arrays.stream(DeadLetterReviewState.values())
                                    .map(Enum::name).collect(java.util.stream.Collectors.joining(", ")));
                    return 1;
                }
            }

            List<DeadLetterRecord> items = store.findDeadLetters(filter, page * size, size);
            if (items.isEmpty()) {
                System.out.println("No dead letters found.");
                return 0;
            }
            System.out.printf("%-36s  %-10s  %-20s  %s%n", "ID", "STATE", "FAILED AT", "GOAL");
            System.out.println("-".repeat(100));
            for (DeadLetterRecord dl : items) {
                System.out.printf("%-36s  %-10s  %-20s  %s%n",
                        dl.id(), dl.reviewState(), dl.failedAt(), dl.goal());
            }
            return 0;
        }
    }

    @Command(name = "replay", mixinStandardHelpOptions = true,
             description = "Replay a dead letter by creating a new execution.")
    static class ReplayCommand implements Callable<Integer> {

        @ParentCommand
        DlqCommand dlq;

        @Parameters(index = "0", description = "Dead letter ID to replay.")
        private String id;

        @Override
        public Integer call() throws Exception {
            NexoraEngine engine = dlq.parent.engine();
            ExecutionStore store = requireStore(engine);
            if (store == null) return 1;

            var dlOpt = store.findDeadLetterById(id);
            if (dlOpt.isEmpty()) {
                System.err.println("Error: dead letter not found: " + id);
                return 1;
            }
            var dl = dlOpt.get();
            if (dl.reviewState() != DeadLetterReviewState.PENDING) {
                System.err.printf("Error: dead letter is %s, not PENDING%n", dl.reviewState());
                return 1;
            }

            System.out.printf("Replaying dead letter %s (goal: %s)%n", id, dl.goal());
            var result = engine.execute(new Intent(dl.goal(), dl.context())).get();
            store.updateDeadLetterState(id, DeadLetterReviewState.REPLAYED, null);
            System.out.printf("Replay complete. New executionId=%s status=%s%n",
                    result.executionId(), result.status());
            return 0;
        }
    }

    @Command(name = "resolve", mixinStandardHelpOptions = true,
             description = "Mark a dead letter as resolved.")
    static class ResolveCommand implements Callable<Integer> {

        @ParentCommand
        DlqCommand dlq;

        @Parameters(index = "0", description = "Dead letter ID to resolve.")
        private String id;

        @Option(names = {"--reason"}, description = "Optional resolution reason.")
        private String reason;

        @Override
        public Integer call() {
            ExecutionStore store = requireStore(dlq.parent.engine());
            if (store == null) return 1;

            var dlOpt = store.findDeadLetterById(id);
            if (dlOpt.isEmpty()) {
                System.err.println("Error: dead letter not found: " + id);
                return 1;
            }

            store.updateDeadLetterState(id, DeadLetterReviewState.RESOLVED, reason);
            System.out.printf("Dead letter %s marked as RESOLVED.%n", id);
            if (reason != null) System.out.println("Reason: " + reason);
            return 0;
        }
    }
}
