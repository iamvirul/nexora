package me.virul.nexora.planner.llm;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import me.virul.nexora.core.plan.InputBinding;
import me.virul.nexora.core.plan.Plan;
import me.virul.nexora.core.plan.Step;
import me.virul.nexora.spi.CapabilityDescriptor;
import me.virul.nexora.spi.ParameterDescriptor;

/**
 * Turns the model's JSON answer into a {@link Plan}, refusing anything the engine could not run.
 * The schema already fixes the shape; this checks what a schema cannot: that every capability
 * exists, step ids are unique, dependencies point at real steps without a cycle, and every required
 * input is bound to a value that will be there: a context name the request actually carries, or a
 * step that runs first.
 *
 * <p>A step input bound to another step's output also makes that step a dependency, since the
 * output is only there once that step has finished. A context name becomes the
 * {@code intent.context.<name>} path the engine resolves request context values under.
 */
final class PlanParser {

    /** Guards against a runaway answer; real plans are a handful of steps. */
    static final int MAX_STEPS = 50;

    /** Where the engine's {@code ExecutionContext} keeps the request's context values. */
    static final String INTENT_CONTEXT_PREFIX = "intent.context.";

    private static final Pattern STEP_ID = Pattern.compile("[A-Za-z0-9_.-]{1,64}");
    private static final ObjectMapper JSON =
            new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private PlanParser() {}

    /** @throws InvalidPlanException with the first problem found */
    static Plan parse(String answer, List<CapabilityDescriptor> capabilities, Set<String> contextNames) {
        PlanJson plan = read(answer);
        if (plan.steps() == null || plan.steps().isEmpty()) {
            throw new InvalidPlanException("the model returned no steps");
        }
        if (plan.steps().size() > MAX_STEPS) {
            throw new InvalidPlanException("the plan has " + plan.steps().size() + " steps, more than " + MAX_STEPS);
        }
        Map<String, CapabilityDescriptor> byId = new HashMap<>();
        capabilities.forEach(c -> byId.put(c.id(), c));
        Set<String> stepIds = stepIds(plan.steps());
        Map<String, CapabilityDescriptor> capabilityByStep = new HashMap<>();
        for (StepJson step : plan.steps()) {
            if (byId.containsKey(step.capabilityId())) {
                capabilityByStep.put(step.id(), byId.get(step.capabilityId()));
            }
        }
        Bindings bindings = new Bindings(stepIds, capabilityByStep, contextNames);

        List<Step> steps = new ArrayList<>();
        for (StepJson step : plan.steps()) {
            steps.add(toStep(step, byId, bindings));
        }
        requireNoCycle(steps);
        return new Plan(List.copyOf(steps));
    }

    private static PlanJson read(String answer) {
        if (answer == null || answer.isBlank()) {
            throw new InvalidPlanException("the answer was empty");
        }
        try {
            return JSON.readValue(answer, PlanJson.class);
        } catch (JsonProcessingException e) {
            throw new InvalidPlanException("the answer is not plan JSON: " + e.getOriginalMessage());
        }
    }

    private static Set<String> stepIds(List<StepJson> steps) {
        Set<String> ids = new HashSet<>();
        for (StepJson step : steps) {
            if (step == null || step.id() == null || !STEP_ID.matcher(step.id()).matches()) {
                throw new InvalidPlanException("a step id is missing or not 1 to 64 of A-Z, a-z, 0-9, '_', '-', '.'");
            }
            if (!ids.add(step.id())) {
                throw new InvalidPlanException("step id '" + step.id() + "' is used twice");
            }
        }
        return ids;
    }

    private static Step toStep(StepJson step, Map<String, CapabilityDescriptor> capabilities, Bindings bindings) {
        Set<String> stepIds = bindings.stepIds();
        CapabilityDescriptor capability = capabilities.get(step.capabilityId());
        if (capability == null) {
            throw new InvalidPlanException(
                    "step '" + step.id() + "' uses unknown capability '" + step.capabilityId() + "'");
        }
        Set<String> dependsOn = new LinkedHashSet<>();
        for (String dependency : step.dependsOn() == null ? List.<String>of() : step.dependsOn()) {
            requireStep(step.id(), dependency, stepIds);
            dependsOn.add(dependency);
        }
        Map<String, InputBinding> inputs = new LinkedHashMap<>();
        for (InputJson input : step.inputs() == null ? List.<InputJson>of() : step.inputs()) {
            if (input == null || input.name() == null || input.name().isBlank()) {
                throw new InvalidPlanException("step '" + step.id() + "' has an input without a name");
            }
            if (inputs.containsKey(input.name())) {
                throw new InvalidPlanException("step '" + step.id() + "' binds input '" + input.name() + "' twice");
            }
            inputs.put(input.name(), binding(step.id(), input, dependsOn, bindings));
        }
        for (ParameterDescriptor parameter : capability.inputParameters()) {
            if (parameter.required() && !inputs.containsKey(parameter.name())) {
                throw new InvalidPlanException("step '" + step.id() + "' does not bind required input '"
                        + parameter.name() + "' of " + capability.id());
            }
        }
        return new Step(step.id(), capability.id(), inputs, null, dependsOn, null, null, null, null);
    }

    private static InputBinding binding(String stepId, InputJson input, Set<String> dependsOn, Bindings bindings) {
        Set<String> stepIds = bindings.stepIds();
        Set<String> contextNames = bindings.contextNames();
        String where = "input '" + input.name() + "' of step '" + stepId + "'";
        return switch (input.source() == null ? "" : input.source()) {
            case PlanSchema.SOURCE_LITERAL -> {
                if (input.value() == null) {
                    throw new InvalidPlanException(where + " is a literal without a value");
                }
                yield InputBinding.literal(input.value());
            }
            case PlanSchema.SOURCE_CONTEXT -> {
                String name = contextName(input.value());
                if (name == null || name.isBlank()) {
                    throw new InvalidPlanException(where + " reads the context without naming a value");
                }
                if (!contextNames.contains(name)) {
                    throw new InvalidPlanException(
                            where + " reads context value '" + name + "', which the request does not carry");
                }
                yield InputBinding.fromContext(INTENT_CONTEXT_PREFIX + name);
            }
            case PlanSchema.SOURCE_STEP -> {
                requireStep(stepId, input.stepId(), stepIds);
                requireDeclaredOutput(where, input, bindings.capabilityByStep().get(input.stepId()));
                dependsOn.add(input.stepId());
                yield input.field() == null || input.field().isBlank()
                        ? InputBinding.fromStep(input.stepId())
                        : InputBinding.fromStep(input.stepId(), input.field());
            }
            default -> throw new InvalidPlanException(where + " has unknown source '" + input.source() + "'");
        };
    }

    /**
     * A named output field must be one the producing capability declares, when it declares any.
     * Capabilities that list no outputs are taken at their word that any field may exist.
     */
    private static void requireDeclaredOutput(String where, InputJson input, CapabilityDescriptor producer) {
        if (input.field() == null || input.field().isBlank() || producer == null) {
            return;
        }
        List<ParameterDescriptor> outputs = producer.outputParameters();
        if (!outputs.isEmpty() && outputs.stream().noneMatch(o -> o.name().equals(input.field()))) {
            throw new InvalidPlanException(
                    where + " reads output '" + input.field() + "', which " + producer.id() + " does not declare");
        }
    }

    /** What every binding in one plan is checked against. */
    private record Bindings(
            Set<String> stepIds, Map<String, CapabilityDescriptor> capabilityByStep, Set<String> contextNames) {}

    /** The context name, accepting the engine's own {@code intent.context.} prefix if the model wrote it. */
    private static String contextName(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.strip();
        return trimmed.startsWith(INTENT_CONTEXT_PREFIX) ? trimmed.substring(INTENT_CONTEXT_PREFIX.length()) : trimmed;
    }

    private static void requireStep(String stepId, String referenced, Set<String> stepIds) {
        if (referenced == null || !stepIds.contains(referenced)) {
            throw new InvalidPlanException("step '" + stepId + "' refers to unknown step '" + referenced + "'");
        }
        if (referenced.equals(stepId)) {
            throw new InvalidPlanException("step '" + stepId + "' depends on itself");
        }
    }

    /** Depth-first search; a step met again while still on the path closes a cycle. */
    private static void requireNoCycle(List<Step> steps) {
        Map<String, Step> byId = new HashMap<>();
        steps.forEach(s -> byId.put(s.id(), s));
        Set<String> done = new HashSet<>();
        Set<String> onPath = new HashSet<>();
        for (Step step : steps) {
            visit(step.id(), byId, done, onPath);
        }
    }

    private static void visit(String id, Map<String, Step> byId, Set<String> done, Set<String> onPath) {
        if (done.contains(id)) {
            return;
        }
        if (!onPath.add(id)) {
            throw new InvalidPlanException("the dependencies form a cycle through step '" + id + "'");
        }
        for (String dependency : byId.get(id).dependsOn()) {
            visit(dependency, byId, done, onPath);
        }
        onPath.remove(id);
        done.add(id);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record PlanJson(List<StepJson> steps) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record StepJson(String id, String capabilityId, List<String> dependsOn, List<InputJson> inputs) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record InputJson(String name, String source, String value, String stepId, String field) {}
}
