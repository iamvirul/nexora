package com.nexora.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.ParentCommand;

import java.util.List;
import java.util.concurrent.Callable;

@Command(
        name = "plugins",
        mixinStandardHelpOptions = true
)
public class PluginsCommand implements Callable<Integer> {

    @ParentCommand
    private NexoraCli parent;

    @Override
    public Integer call() {
        List<String> active = parent.engine().activePluginIds();

        if (active.isEmpty()) {
            System.out.println("No plugins active.");
            return 0;
        }

        System.out.println("Active plugins:");
        active.forEach(id -> System.out.println("  • " + id));
        return 0;
    }
}
