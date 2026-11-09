package com.nexora.planner.llm;

import java.util.List;
import java.util.Map;

/**
 * The JSON schema every provider is asked to answer with. It stays within what both Anthropic and
 * OpenAI strict structured outputs accept: every object closed with {@code additionalProperties:
 * false}, every property required (optional values are nullable), and no recursion or numeric or
 * length constraints. Anything the schema cannot express is checked by {@link PlanParser}.
 *
 * <pre>{@code
 * {"steps": [{"id": "charge", "capabilityId": "charge_card", "dependsOn": ["validate"],
 *             "inputs": [{"name": "amount", "source": "context", "value": "order.total",
 *                         "stepId": null, "field": null}]}]}
 * }</pre>
 *
 * An input's {@code source} is {@code literal} (the string in {@code value}), {@code context} (the
 * name of a request context value in {@code value}), or {@code step} (the output of {@code stepId}, or its
 * {@code field} when set).
 */
public final class PlanSchema {

    /** The schema's name, sent where a provider asks for one (OpenAI's {@code json_schema}). */
    public static final String NAME = "nexora_plan";

    static final String SOURCE_LITERAL = "literal";
    static final String SOURCE_CONTEXT = "context";
    static final String SOURCE_STEP = "step";

    private static final Map<String, Object> STRING = Map.of("type", "string");
    private static final Map<String, Object> NULLABLE_STRING = Map.of("anyOf", List.of(STRING, Map.of("type", "null")));

    private static final Map<String, Object> INPUT = Map.of(
            "type",
            "object",
            "additionalProperties",
            false,
            "required",
            List.of("name", "source", "value", "stepId", "field"),
            "properties",
            Map.of(
                    "name", STRING,
                    "source", Map.of("type", "string", "enum", List.of(SOURCE_LITERAL, SOURCE_CONTEXT, SOURCE_STEP)),
                    "value", NULLABLE_STRING,
                    "stepId", NULLABLE_STRING,
                    "field", NULLABLE_STRING));

    private static final Map<String, Object> STEP = Map.of(
            "type",
            "object",
            "additionalProperties",
            false,
            "required",
            List.of("id", "capabilityId", "dependsOn", "inputs"),
            "properties",
            Map.of(
                    "id",
                    STRING,
                    "capabilityId",
                    STRING,
                    "dependsOn",
                    Map.of("type", "array", "items", STRING),
                    "inputs",
                    Map.of("type", "array", "items", INPUT)));

    private static final Map<String, Object> SCHEMA = Map.of(
            "type",
            "object",
            "additionalProperties",
            false,
            "required",
            List.of("steps"),
            "properties",
            Map.of("steps", Map.of("type", "array", "items", STEP)));

    private PlanSchema() {}

    /** The schema as plain maps and lists, ready to hand to either SDK. */
    public static Map<String, Object> schema() {
        return SCHEMA;
    }
}
