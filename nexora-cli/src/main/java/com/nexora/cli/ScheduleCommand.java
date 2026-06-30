package com.nexora.cli;

import com.nexora.api.NexoraEngine;
import com.nexora.core.intent.Intent;
import com.nexora.persistence.MissedFirePolicy;
import com.nexora.persistence.ScheduleRecord;
import com.nexora.runtime.scheduler.ScheduledExecution;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

@Command(
        name = "schedule",
        mixinStandardHelpOptions = true,
        description = "Manage recurring cron-based executions.",
        subcommands = {
                ScheduleCommand.AddCommand.class,
                ScheduleCommand.ListCommand.class,
                ScheduleCommand.RemoveCommand.class
        }
)
public class ScheduleCommand implements Callable<Integer> {

    @ParentCommand
    NexoraCli parent;

    @Override
    public Integer call() {
        System.out.println("Usage: nexora schedule <add|list|remove>");
        return 0;
    }

    @Command(name = "add", mixinStandardHelpOptions = true,
             description = "Register a new recurring schedule.")
    static class AddCommand implements Callable<Integer> {

        @ParentCommand
        ScheduleCommand scheduleCmd;

        @Option(names = {"--goal"}, required = true, description = "Intent goal string.")
        private String goal;

        @Option(names = {"--cron"}, required = true,
                description = "5-field UNIX cron expression (e.g. \"0 0 * * *\").")
        private String cron;

        @Option(names = {"--policy"}, defaultValue = "FIRE_ONCE",
                description = "Missed-fire policy: SKIP, FIRE_ONCE, FIRE_ALL. Default: ${DEFAULT-VALUE}")
        private String policy;

        @Override
        public Integer call() {
            MissedFirePolicy missedFirePolicy;
            try {
                missedFirePolicy = MissedFirePolicy.valueOf(policy.toUpperCase());
            } catch (IllegalArgumentException e) {
                System.err.printf("Invalid policy '%s'. Allowed: SKIP, FIRE_ONCE, FIRE_ALL%n", policy);
                return 1;
            }

            NexoraEngine engine;
            try {
                engine = scheduleCmd.parent.engine();
            } catch (Exception e) {
                System.err.println("Error: failed to build engine — " + e.getMessage());
                return 1;
            }

            ScheduledExecution handle;
            try {
                handle = engine.schedule(cron, new Intent(goal, Map.of()), missedFirePolicy);
            } catch (IllegalStateException e) {
                System.err.println("Error: " + e.getMessage());
                return 1;
            } catch (IllegalArgumentException e) {
                System.err.println("Invalid cron expression: " + e.getMessage());
                return 1;
            }

            System.out.printf("Schedule registered.%n");
            System.out.printf("  ID:        %s%n", handle.id());
            System.out.printf("  Cron:      %s%n", handle.cronExpression());
            System.out.printf("  Next fire: %s%n", handle.nextFireTime());
            System.out.printf("  Policy:    %s%n", missedFirePolicy);
            return 0;
        }
    }

    @Command(name = "list", mixinStandardHelpOptions = true,
             description = "List all registered schedules.")
    static class ListCommand implements Callable<Integer> {

        @ParentCommand
        ScheduleCommand scheduleCmd;

        @Option(names = {"--active-only"}, description = "Show only active schedules.")
        private boolean activeOnly;

        @Override
        public Integer call() {
            NexoraEngine engine;
            try {
                engine = scheduleCmd.parent.engine();
            } catch (Exception e) {
                System.err.println("Error: failed to build engine — " + e.getMessage());
                return 1;
            }

            List<ScheduleRecord> records;
            try {
                records = activeOnly ? engine.listActiveSchedules() : engine.listSchedules();
            } catch (IllegalStateException e) {
                System.err.println("Error: " + e.getMessage());
                return 1;
            }

            if (records.isEmpty()) {
                System.out.println("No schedules found.");
                return 0;
            }

            System.out.printf("%-36s  %-6s  %-20s  %-20s  %-12s  %-12s  %s%n",
                    "ID", "ACTIVE", "NEXT FIRE", "LAST FIRE", "POLICY", "LAST STATUS", "GOAL");
            System.out.println("-".repeat(136));
            for (ScheduleRecord r : records) {
                System.out.printf("%-36s  %-6s  %-20s  %-20s  %-12s  %-12s  %s%n",
                        r.id(),
                        r.active() ? "YES" : "NO",
                        r.nextFireAt(),
                        r.lastFiredAt() != null ? r.lastFiredAt() : "-",
                        r.missedFirePolicy(),
                        r.lastStatus(),
                        r.goal());
            }
            return 0;
        }
    }

    @Command(name = "remove", mixinStandardHelpOptions = true,
             description = "Cancel and remove a schedule.")
    static class RemoveCommand implements Callable<Integer> {

        @ParentCommand
        ScheduleCommand scheduleCmd;

        @Parameters(index = "0", description = "Schedule ID to remove.")
        private String id;

        @Override
        public Integer call() {
            NexoraEngine engine;
            try {
                engine = scheduleCmd.parent.engine();
            } catch (Exception e) {
                System.err.println("Error: failed to build engine — " + e.getMessage());
                return 1;
            }

            try {
                engine.cancelSchedule(id);
            } catch (IllegalStateException e) {
                System.err.println("Error: " + e.getMessage());
                return 1;
            }

            System.out.printf("Schedule %s cancelled.%n", id);
            return 0;
        }
    }
}
