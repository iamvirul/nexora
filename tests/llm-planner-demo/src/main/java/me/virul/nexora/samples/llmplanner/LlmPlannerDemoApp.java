package me.virul.nexora.samples.llmplanner;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import me.virul.nexora.api.NexoraEngine;
import me.virul.nexora.core.capability.CapabilityResult;
import me.virul.nexora.core.execution.ExecutionResult;
import me.virul.nexora.core.execution.ExecutionStatus;
import me.virul.nexora.core.intent.Intent;
import me.virul.nexora.core.plan.InputBinding;
import me.virul.nexora.planner.model.StepDefinition;
import me.virul.nexora.spi.Capability;
import me.virul.nexora.spi.CapabilityDescriptor;
import me.virul.nexora.spi.CapabilityProvider;
import me.virul.nexora.spi.NexoraPlugin;
import me.virul.nexora.spi.ParameterDescriptor;
import me.virul.nexora.spi.PluginContext;
import me.virul.nexora.spi.PluginDescriptor;
import org.slf4j.LoggerFactory;

/**
 * Runs one goal twice: once with the built-in rule-based planner, once with the LLM planner plugin
 * loaded from its JAR, and prints the steps each plan ran with the inputs each step received.
 *
 * <pre>
 *   java -jar tests/llm-planner-demo/target/llm-planner-demo.jar --offline
 *   ANTHROPIC_API_KEY=... java -jar tests/llm-planner-demo/target/llm-planner-demo.jar
 *   java -jar ... --goal "..." --plugin path/to/nexora-planner-llm-plugin.jar
 * </pre>
 *
 * Without {@code --offline}, the plugin reads its usual settings ({@code NEXORA_LLM_PROVIDER},
 * the provider's API key, {@code NEXORA_LLM_MODEL}, ...) and calls the real model.
 */
public final class LlmPlannerDemoApp {

    static final String DEFAULT_GOAL = "Charge order ORD-42 and email the customer a receipt for the payment";
    static final Path DEFAULT_PLUGIN = Path.of("tests/llm-planner-demo/target/plugins/nexora-planner-llm-plugin.jar");

    private static final String PLANNER_LOGGER = "me.virul.nexora.planner.llm.LlmPlanner";
    /** The plugin's own logger, which warns when no provider is configured. */
    private static final String PLUGIN_LOGGER = "nexora.plugin.nexora-planner-llm";

    private static final int EXIT_USAGE = 2;
    private static final long RUN_TIMEOUT_SECONDS = 120;

    /** One step as it ran: the capability and the inputs it was given. */
    public record RanStep(String capabilityId, Map<String, Object> inputs) {}

    /** What each run did, for the test and for printing. */
    public record Outcome(
            List<RanStep> ruleSteps,
            ExecutionStatus ruleStatus,
            List<RanStep> llmSteps,
            ExecutionStatus llmStatus,
            boolean llmFellBack) {}

    private LlmPlannerDemoApp() {}

    public static void main(String[] args) throws Exception {
        Options options;
        try {
            options = Options.parse(args);
        } catch (IllegalArgumentException e) {
            System.err.println("Error: " + e.getMessage());
            System.err.println("Usage: llm-planner-demo [--offline] [--goal TEXT] [--plugin PATH]");
            System.exit(EXIT_USAGE);
            return;
        }
        if (!Files.isRegularFile(options.plugin())) {
            System.err.println("Error: plugin JAR not found at " + options.plugin()
                    + ". Build it with `mvn package -pl tests/llm-planner-demo -am`, or pass --plugin.");
            System.exit(EXIT_USAGE);
            return;
        }
        if (options.offline()) {
            try (OfflineModel model = OfflineModel.start()) {
                run(options, System.out);
            }
        } else {
            run(options, System.out);
        }
    }

    /** Runs both planners on {@code options.goal()}, printing as it goes. */
    public static Outcome run(Options options, PrintStream out) throws Exception {
        out.println("Goal: \"" + options.goal() + "\"");
        out.println(
                options.offline()
                        ? "LLM: offline, a local stand-in for the Anthropic API answers with a canned plan"
                        : "LLM: configured from NEXORA_LLM_* and the provider API key");
        out.println();

        Run rules = execute(options, false);
        print(out, "Rule-based planner (keyword step definitions)", rules);

        ListAppender<ILoggingEvent> plannerLog = capture(PLANNER_LOGGER);
        ListAppender<ILoggingEvent> pluginLog = capture(PLUGIN_LOGGER);
        Run llm;
        try {
            llm = execute(options, true);
        } finally {
            release(PLANNER_LOGGER, plannerLog);
            release(PLUGIN_LOGGER, pluginLog);
        }
        boolean off = warned(pluginLog);
        boolean declined = warned(plannerLog);
        String title = off
                ? "LLM planner (off, so the rule-based plan ran)"
                : declined ? "LLM planner (declined, so the rule-based plan ran)" : "LLM planner";
        print(out, title, llm);
        if (off) {
            out.println("Set NEXORA_LLM_PROVIDER and the provider's API key to call a real model, or pass --offline.");
        }
        return new Outcome(rules.steps(), rules.status(), llm.steps(), llm.status(), off || declined);
    }

    private record Run(List<RanStep> steps, ExecutionStatus status) {}

    private static ListAppender<ILoggingEvent> capture(String loggerName) {
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        ((Logger) LoggerFactory.getLogger(loggerName)).addAppender(appender);
        return appender;
    }

    private static void release(String loggerName, ListAppender<ILoggingEvent> appender) {
        ((Logger) LoggerFactory.getLogger(loggerName)).detachAppender(appender);
    }

    private static boolean warned(ListAppender<ILoggingEvent> appender) {
        return appender.list.stream().anyMatch(e -> e.getLevel().isGreaterOrEqual(Level.WARN));
    }

    private static Run execute(Options options, boolean withLlm) throws Exception {
        List<RanStep> ran = new CopyOnWriteArrayList<>();
        NexoraEngine.Builder builder = NexoraEngine.builder().withPlugin(new ShopCapabilities(ran));
        ruleDefinitions().forEach(builder::withStepDefinition);
        if (withLlm) {
            builder.withPluginJar(options.plugin());
        }
        try (NexoraEngine engine = builder.build()) {
            ExecutionResult result = engine.execute(new Intent(options.goal(), orderContext()))
                    .get(RUN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            return new Run(List.copyOf(ran), result.status());
        }
    }

    /** What a keyword rule set for this shop looks like: one rule per word the goal may contain. */
    static List<StepDefinition> ruleDefinitions() {
        return List.of(
                StepDefinition.builder("validate", "validate_order")
                        .withMatcher(goal -> goal.toLowerCase().contains("order"))
                        .withInput("orderId", InputBinding.fromContext("intent.context.orderId"))
                        .build(),
                StepDefinition.builder("charge", "charge_card")
                        .withMatcher(goal -> goal.toLowerCase().contains("charge"))
                        .withInput("orderId", InputBinding.fromContext("intent.context.orderId"))
                        .withInput("amount", InputBinding.fromContext("intent.context.amount"))
                        .dependsOn("validate")
                        .build(),
                StepDefinition.builder("receipt", "send_receipt")
                        .withMatcher(goal -> goal.toLowerCase().contains("receipt"))
                        .withInput("orderId", InputBinding.fromContext("intent.context.orderId"))
                        .withInput("email", InputBinding.fromContext("intent.context.email"))
                        .dependsOn("charge")
                        .build());
    }

    static Map<String, Object> orderContext() {
        return Map.of("orderId", "ORD-42", "amount", "42.00", "email", "ada@example.com", "sku", "BOOK-1");
    }

    private static void print(PrintStream out, String title, Run run) {
        out.println(title + ": " + run.status());
        int index = 1;
        for (RanStep step : run.steps()) {
            out.printf("  %d. %-15s %s%n", index++, step.capabilityId(), new TreeMap<>(step.inputs()));
        }
        out.println();
    }

    /** Command-line options. */
    public record Options(String goal, Path plugin, boolean offline) {
        public Options {
            Objects.requireNonNull(goal, "goal");
            Objects.requireNonNull(plugin, "plugin");
        }

        static Options parse(String[] args) {
            String goal = DEFAULT_GOAL;
            Path plugin = DEFAULT_PLUGIN;
            boolean offline = false;
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--offline" -> offline = true;
                    case "--goal" -> goal = value(args, ++i, "--goal");
                    case "--plugin" -> plugin = Path.of(value(args, ++i, "--plugin"));
                    default -> throw new IllegalArgumentException("unknown option " + args[i]);
                }
            }
            if (goal.isBlank()) {
                throw new IllegalArgumentException("--goal must not be blank");
            }
            return new Options(goal, plugin, offline);
        }

        private static String value(String[] args, int index, String option) {
            if (index >= args.length) {
                throw new IllegalArgumentException(option + " needs a value");
            }
            return args[index];
        }
    }

    /**
     * The shop's capabilities. The LLM sees all of them and picks what the goal needs; each records
     * the inputs it was called with, which shows how the plan wired the steps together.
     */
    private static final class ShopCapabilities implements NexoraPlugin {
        private final List<RanStep> ran;

        ShopCapabilities(List<RanStep> ran) {
            this.ran = ran;
        }

        @Override
        public PluginDescriptor descriptor() {
            return new PluginDescriptor("demo-shop", "1.0.0", "Order capabilities for the demo", List.of(), null);
        }

        @Override
        public void initialize(PluginContext context) {
            // nothing to set up
        }

        @Override
        public List<CapabilityProvider> capabilityProviders() {
            List<CapabilityProvider> providers = new ArrayList<>();
            providers.add(provider(
                    "validate_order",
                    "Checks that the order exists and can be paid",
                    Map.of("status", "valid"),
                    param("orderId", true, "Order to validate")));
            providers.add(provider(
                    "fraud_check",
                    "Scores the order for payment fraud",
                    Map.of("risk", "low"),
                    param("orderId", true, "Order to score")));
            providers.add(provider(
                    "charge_card",
                    "Charges the customer's card for the order",
                    Map.of("chargeId", "ch_1001"),
                    param("orderId", true, "Order to charge"),
                    param("amount", true, "Amount to charge")));
            providers.add(provider(
                    "send_receipt",
                    "Emails the customer a receipt for a charge",
                    Map.of("sent", true),
                    param("orderId", true, "Order the receipt is for"),
                    param("email", true, "Customer email address"),
                    param("chargeId", false, "Charge the receipt confirms")));
            providers.add(provider(
                    "reserve_stock",
                    "Reserves stock for an item",
                    Map.of("reserved", true),
                    param("sku", true, "Item to reserve")));
            return providers;
        }

        private CapabilityProvider provider(
                String id, String description, Map<String, Object> output, ParameterDescriptor... inputs) {
            CapabilityDescriptor descriptor =
                    new CapabilityDescriptor(id, description, List.of(inputs), outputsOf(output), true, false);
            return new CapabilityProvider() {
                @Override
                public CapabilityDescriptor descriptor() {
                    return descriptor;
                }

                @Override
                public Capability create(PluginContext context) {
                    return request -> {
                        ran.add(new RanStep(id, Map.copyOf(request.inputs())));
                        return CapabilityResult.success(output);
                    };
                }
            };
        }

        /** Declares each field the capability returns, so a planner can bind to it by name. */
        private static List<ParameterDescriptor> outputsOf(Map<String, Object> output) {
            return output.keySet().stream()
                    .sorted()
                    .map(name -> new ParameterDescriptor(name, "string", true, null))
                    .toList();
        }

        private static ParameterDescriptor param(String name, boolean required, String description) {
            return new ParameterDescriptor(name, "string", required, description);
        }

        @Override
        public void shutdown() {
            // nothing to release
        }
    }
}
