package com.nexora.planner.llm;

import java.util.Map;
import java.util.Objects;

/**
 * What the planner sends: a fixed system prompt, the per-intent user prompt, and the JSON schema
 * the answer must follow. The schema is the same for every request so providers can cache it.
 */
public record PlanPrompt(String system, String user, Map<String, Object> schema) {
    /** Copies the schema so the prompt cannot change after it is built. */
    public PlanPrompt {
        Objects.requireNonNull(system, "system must not be null");
        Objects.requireNonNull(user, "user must not be null");
        schema = Map.copyOf(Objects.requireNonNull(schema, "schema must not be null"));
    }
}
