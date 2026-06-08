package com.nexora.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexora.api.NexoraEngine;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Model.CommandSpec;

import java.io.File;
import java.util.concurrent.Callable;

@Command(
        name = "nexora",
        mixinStandardHelpOptions = true,
        version = "Nexora 1.0-SNAPSHOT",
        subcommands = {
                RunCommand.class,
                PlanCommand.class,
                CapsCommand.class,
                PluginsCommand.class,
                ObserveCommand.class,
                DemoCommand.class,
                DlqCommand.class,
                CommandLine.HelpCommand.class
        }
)
public class NexoraCli implements Callable<Integer> {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Spec
    CommandSpec spec;

    @Option(names = {"-c", "--config"},
            description = "Path to config file (JSON). Default: ./nexora.json",
            defaultValue = "nexora.json")
    private File configFile;

    @Option(names = {"-v", "--verbose"}, description = "Enable engine debug logging.")
    boolean verbose;

    // Lazily initialised — not all subcommands need the engine (e.g. plan, demo build their own)
    private NexoraEngine _engine;
    private CliConfig _config;

    /** Returns the loaded config, falling back to an empty default if the file is absent. */
    CliConfig config() {
        if (_config != null) return _config;
        if (configFile.exists()) {
            try {
                _config = JSON.readValue(configFile, CliConfig.class);
            } catch (Exception e) {
                spec.commandLine().getErr().println(
                        "Warning: could not parse config file " + configFile + " — " + e.getMessage());
                _config = new CliConfig();
            }
        } else {
            _config = new CliConfig();
        }
        return _config;
    }

    /** Returns a shared engine wired from the loaded config. */
    NexoraEngine engine() {
        if (_engine == null) {
            _engine = EngineFactory.fromConfig(config());
        }
        return _engine;
    }

    /** Root command with no subcommand — print help. */
    @Override
    public Integer call() {
        spec.commandLine().usage(spec.commandLine().getOut());
        return 0;
    }

    public static void main(String[] args) {
        CommandLine cmd = new CommandLine(new NexoraCli())
                .setCaseInsensitiveEnumValuesAllowed(true)
                .setUsageHelpAutoWidth(true);
        HelpLoader.apply(cmd);
        System.exit(cmd.execute(args));
    }
}
