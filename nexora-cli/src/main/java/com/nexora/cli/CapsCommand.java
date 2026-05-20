package com.nexora.cli;

import com.nexora.api.NexoraEngine;
import com.nexora.spi.CapabilityDescriptor;
import picocli.CommandLine.Command;
import picocli.CommandLine.ParentCommand;

import java.util.List;
import java.util.concurrent.Callable;

@Command(
        name = "caps",
        description = "List all registered capabilities.",
        mixinStandardHelpOptions = true
)
public class CapsCommand implements Callable<Integer> {

    @ParentCommand
    private NexoraCli parent;

    @Override
    public Integer call() {
        NexoraEngine engine = parent.engine();
        List<CapabilityDescriptor> caps = engine.listCapabilities();

        if (caps.isEmpty()) {
            System.out.println("No capabilities registered.");
            System.out.println("Load a plugin or check your nexora.json config.");
            return 0;
        }

        System.out.printf("%-28s  %-8s  %s%n", "CAPABILITY ID", "IDEMPOTENT", "DESCRIPTION");
        System.out.println("─".repeat(72));
        for (CapabilityDescriptor cap : caps) {
            System.out.printf("%-28s  %-8s  %s%n",
                    cap.id(),
                    cap.idempotent() ? "yes" : "no",
                    cap.description());
        }
        System.out.printf("%n%d capability(ies) registered.%n", caps.size());
        return 0;
    }
}
