package me.virul.nexora.core.context;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import me.virul.nexora.core.intent.Intent;

/**
 * The live state of one execution: its intent, its trace, the values steps write to the shared context,
 * the outputs of completed steps and the input overrides requested by plan amendments.
 *
 * <p>Thread-safe: steps that run in parallel read and write it concurrently. It holds no {@code null}
 * values.
 */
public final class ExecutionContext {

    private final String executionId;
    private final Intent intent;
    private final TraceContext traceContext;
    private final ConcurrentHashMap<String, Object> data = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Object> stepOutputs = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, Object>> inputOverrides =
            new ConcurrentHashMap<>();
    private final Map<String, String> reusedIdempotencyKeys;

    /**
     * Creates the context of a new execution, with a random execution id.
     *
     * @param intent       the intent being executed
     * @param traceContext the root span of the execution
     * @throws NullPointerException if either argument is null
     */
    public ExecutionContext(Intent intent, TraceContext traceContext) {
        this(intent, traceContext, Map.of());
    }

    /**
     * Creates the context of a replay, with a random execution id.
     *
     * @param intent                the intent being executed
     * @param traceContext          the root span of the execution
     * @param reusedIdempotencyKeys idempotency keys to reuse per step id, so a replay sends a step the
     *                              same key it completed with before and the capability can skip
     *                              repeating its side effect
     * @throws NullPointerException if any argument is null
     */
    public ExecutionContext(Intent intent, TraceContext traceContext, Map<String, String> reusedIdempotencyKeys) {
        this.executionId = UUID.randomUUID().toString();
        this.intent = Objects.requireNonNull(intent, "intent must not be null");
        this.traceContext = Objects.requireNonNull(traceContext, "traceContext must not be null");
        this.reusedIdempotencyKeys =
                Map.copyOf(Objects.requireNonNull(reusedIdempotencyKeys, "reusedIdempotencyKeys must not be null"));
    }

    /**
     * The idempotency key to send a step.
     *
     * @param stepId the step
     * @return the reused key when replaying a step that already completed, otherwise a fresh random one
     */
    public String idempotencyKeyFor(String stepId) {
        String reused = reusedIdempotencyKeys.get(stepId);
        return reused != null ? reused : UUID.randomUUID().toString();
    }

    /**
     * Stores a value in the shared context.
     *
     * @param key   the key
     * @param value the value; {@code null} removes the key, as the context holds no nulls
     */
    public void put(String key, Object value) {
        if (value == null) {
            data.remove(key);
        } else {
            data.put(key, value);
        }
    }

    /**
     * Reads a value from the shared context.
     *
     * @param <T>  the expected type
     * @param key  the key
     * @param type the expected type
     * @return the value, or empty when the key is absent
     * @throws ClassCastException if the value is not a {@code type}
     */
    public <T> Optional<T> get(String key, Class<T> type) {
        return Optional.ofNullable(data.get(key)).map(type::cast);
    }

    /**
     * A snapshot of the shared context.
     *
     * @return an immutable copy of every key and value
     */
    public Map<String, Object> getAll() {
        return Map.copyOf(data);
    }

    /**
     * Called by the executor after a step completes successfully. A {@code null} output, from a
     * capability that succeeds with nothing to return, clears any earlier output of the step.
     *
     * @param stepId the step that completed
     * @param output its output, or {@code null}
     */
    public void recordStepOutput(String stepId, Object output) {
        if (output == null) {
            stepOutputs.remove(stepId);
        } else {
            stepOutputs.put(stepId, output);
        }
    }

    /**
     * Retrieves output from a completed step.
     *
     * @param stepId the step
     * @param field  a key of a {@code Map} output, or {@code null} for the whole output. When the
     *               output is not a {@code Map}, the whole output is returned whatever the field
     * @return the output or field, or {@code null} when the step has produced no output
     */
    public Object getStepOutput(String stepId, String field) {
        Object output = stepOutputs.get(stepId);
        if (field == null) return output;
        if (output instanceof Map<?, ?> map) return map.get(field);
        return output;
    }

    /**
     * Resolves a dotted path such as {@code intent.context.userId} or {@code context.orderId}.
     *
     * <p>{@code intent} is the intent itself, {@code intent.goal} its goal and
     * {@code intent.context.<key>} a value of its context. {@code context} is the whole shared context
     * and {@code context.<key>} one of its values. Any other path is looked up as a key of the shared
     * context.
     *
     * @param path the path, or {@code null}
     * @return the value, or {@code null} when the path is null or resolves to nothing
     */
    public Object resolvePath(String path) {
        if (path == null) return null;
        String[] parts = path.split("\\.", 2);
        return switch (parts[0]) {
            case "intent" -> parts.length > 1 ? resolveIntentPath(parts[1]) : intent;
            case "context" -> parts.length > 1 ? data.get(parts[1]) : data;
            default -> data.get(path);
        };
    }

    private Object resolveIntentPath(String subPath) {
        String[] parts = subPath.split("\\.", 2);
        return switch (parts[0]) {
            case "goal" -> intent.getGoal();
            case "context" -> parts.length > 1 ? intent.getContext().get(parts[1]) : intent.getContext();
            default -> null;
        };
    }

    /**
     * Overrides one input of a pending step, taking precedence over its declared binding.
     *
     * @param stepId   the step
     * @param inputKey the input to override
     * @param value    the value to use; must not be null
     * @throws NullPointerException if {@code value} is null
     */
    public void putInputOverride(String stepId, String inputKey, Object value) {
        inputOverrides.computeIfAbsent(stepId, k -> new ConcurrentHashMap<>()).put(inputKey, value);
    }

    /**
     * The input overrides recorded for a step.
     *
     * @param stepId the step
     * @return an immutable copy of its overrides by input key; empty when there are none
     */
    public Map<String, Object> getInputOverrides(String stepId) {
        ConcurrentHashMap<String, Object> overrides = inputOverrides.get(stepId);
        return overrides != null ? Map.copyOf(overrides) : Map.of();
    }

    /**
     * The id of this execution.
     *
     * @return the execution id, a random UUID
     */
    public String getExecutionId() {
        return executionId;
    }

    /**
     * The intent being executed.
     *
     * @return the intent
     */
    public Intent getIntent() {
        return intent;
    }

    /**
     * The tenant this execution belongs to, for capabilities that keep per-tenant state or credentials.
     *
     * @return the tenant id of the intent; never null
     */
    public String tenantId() {
        return intent.getTenantId();
    }

    /**
     * The root span of this execution.
     *
     * @return the trace context
     */
    public TraceContext getTraceContext() {
        return traceContext;
    }
}
