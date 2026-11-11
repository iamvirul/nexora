package me.virul.nexora.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.File;
import java.util.concurrent.Callable;
import java.util.function.UnaryOperator;
import me.virul.nexora.api.NexoraEngine;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

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
            ScheduleCommand.class,
            CancelCommand.class,
            ReplayCommand.class,
            UserCommand.class,
            LoginCommand.class,
            LogoutCommand.class,
            TlsGenCommand.class,
            CommandLine.HelpCommand.class
        })
public class NexoraCli implements Callable<Integer> {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int EXIT_USAGE = 2;

    @Spec
    CommandSpec spec;

    @Option(
            names = {"-c", "--config"},
            description = "Path to config file (JSON). Default: ./nexora.json",
            defaultValue = "nexora.json")
    private File configFile;

    @Option(
            names = {"-v", "--verbose"},
            description = "Enable engine debug logging.")
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
                spec.commandLine()
                        .getErr()
                        .println("Warning: could not parse config file " + configFile + " — " + e.getMessage());
                _config = new CliConfig();
            }
        } else {
            _config = new CliConfig();
        }
        return _config;
    }

    /**
     * Returns a shared engine wired from the loaded config.
     *
     * @throws EngineStartupException if the config does not describe an engine that can start, such
     *     as a store that cannot be opened
     */
    NexoraEngine engine() {
        if (_engine == null) {
            _engine = startEngine(config(), System::getenv);
        }
        return _engine;
    }

    static NexoraEngine startEngine(CliConfig config, UnaryOperator<String> env) {
        try {
            return EngineFactory.fromConfig(config, env);
        } catch (IllegalArgumentException | IllegalStateException e) {
            throw EngineStartupException.of(e);
        }
    }

    /**
     * Prints an {@link EngineStartupException} as one line and exits 2, like other configuration
     * errors. Anything else is a bug and keeps picocli's default handling, the stack trace included.
     */
    static CommandLine withStartupErrorHandling(CommandLine cmd) {
        return cmd.setExecutionExceptionHandler((ex, commandLine, parseResult) -> {
            if (ex instanceof EngineStartupException startup) {
                commandLine.getErr().println("Error: " + startup.getMessage());
                return EXIT_USAGE;
            }
            throw ex;
        });
    }

    /** Root command with no subcommand — print help. */
    @Override
    public Integer call() {
        spec.commandLine().usage(spec.commandLine().getOut());
        return 0;
    }

    public static void main(String[] args) {
        CommandLine cmd = withStartupErrorHandling(new CommandLine(new NexoraCli())
                .setCaseInsensitiveEnumValuesAllowed(true)
                .setUsageHelpAutoWidth(true));
        HelpLoader.apply(cmd);
        System.exit(cmd.execute(args));
    }
}
