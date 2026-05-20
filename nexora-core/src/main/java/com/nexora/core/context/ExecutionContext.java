package com.nexora.core.context;

import com.nexora.core.intent.Intent;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class ExecutionContext {

    private final String executionId;
    private final Intent intent;
    private final TraceContext traceContext;
    private final ConcurrentHashMap<String, Object> data = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Object> stepOutputs = new ConcurrentHashMap<>();
    // stepId -> (inputKey -> overrideValue), populated by ModifyInputAmendment
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, Object>> inputOverrides = new ConcurrentHashMap<>();

    public ExecutionContext(Intent intent, TraceContext traceContext) {
        this.executionId = UUID.randomUUID().toString();
        this.intent = Objects.requireNonNull(intent, "intent must not be null");
        this.traceContext = Objects.requireNonNull(traceContext, "traceContext must not be null");
    }

    public void put(String key, Object value) {
        data.put(key, value);
    }

    public <T> Optional<T> get(String key, Class<T> type) {
        return Optional.ofNullable(data.get(key)).map(type::cast);
    }

    public Map<String, Object> getAll() {
        return Map.copyOf(data);
    }

    /** Called by the executor after a step completes successfully. */
    public void recordStepOutput(String stepId, Object output) {
        stepOutputs.put(stepId, output);
    }

    /**
     * Retrieves output from a completed step.
     * When field is null, the whole output object is returned.
     * When the output is a Map, the named field is extracted.
     */
    public Object getStepOutput(String stepId, String field) {
        Object output = stepOutputs.get(stepId);
        if (field == null) return output;
        if (output instanceof Map<?, ?> map) return map.get(field);
        return output;
    }

    /**
     * Simple dotted-path resolution: "intent.context.userId" or "context.orderId".
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
            case "context" -> parts.length > 1
                    ? intent.getContext().get(parts[1])
                    : intent.getContext();
            default -> null;
        };
    }

    public void putInputOverride(String stepId, String inputKey, Object value) {
        inputOverrides.computeIfAbsent(stepId, k -> new ConcurrentHashMap<>()).put(inputKey, value);
    }

    public Map<String, Object> getInputOverrides(String stepId) {
        ConcurrentHashMap<String, Object> overrides = inputOverrides.get(stepId);
        return overrides != null ? Map.copyOf(overrides) : Map.of();
    }

    public String getExecutionId() { return executionId; }
    public Intent getIntent() { return intent; }
    public TraceContext getTraceContext() { return traceContext; }
}
