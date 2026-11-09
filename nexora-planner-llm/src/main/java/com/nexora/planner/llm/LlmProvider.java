package com.nexora.planner.llm;

import java.util.Locale;
import java.util.Optional;

/**
 * The LLM APIs the planner can call. OpenRouter speaks the OpenAI API, so it shares that client;
 * Anthropic, OpenAI and Gemini each use their vendor's official SDK.
 */
public enum LlmProvider {
    /** Claude, through the official Anthropic Java SDK. */
    ANTHROPIC("ANTHROPIC_API_KEY", null, "claude-opus-5-5"),
    /** OpenAI, through the official OpenAI Java SDK. */
    OPENAI("OPENAI_API_KEY", null, null),
    /** Any model OpenRouter routes to, through the OpenAI Java SDK pointed at OpenRouter. */
    OPENROUTER("OPENROUTER_API_KEY", "https://openrouter.ai/api/v1", null),
    /** Gemini on the Gemini Developer API, through Google's official Gen AI Java SDK. */
    GEMINI("GEMINI_API_KEY", null, null);

    private final String apiKeyEnvVar;
    private final String defaultBaseUrl;
    private final String defaultModel;

    LlmProvider(String apiKeyEnvVar, String defaultBaseUrl, String defaultModel) {
        this.apiKeyEnvVar = apiKeyEnvVar;
        this.defaultBaseUrl = defaultBaseUrl;
        this.defaultModel = defaultModel;
    }

    /** The environment variable holding this provider's API key. */
    public String apiKeyEnvVar() {
        return apiKeyEnvVar;
    }

    /** The API base URL used when none is configured; empty means the SDK's own default. */
    public Optional<String> defaultBaseUrl() {
        return Optional.ofNullable(defaultBaseUrl);
    }

    /**
     * The model used when none is configured. Only Anthropic has one: OpenAI, OpenRouter and Gemini
     * model names change too often to hard-code, so those providers require an explicit model.
     */
    public Optional<String> defaultModel() {
        return Optional.ofNullable(defaultModel);
    }

    /** @throws IllegalArgumentException for a name that is not a provider */
    public static LlmProvider parse(String name) {
        try {
            return valueOf(name.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Unknown LLM provider '" + name + "'; use anthropic, openai, openrouter or gemini", e);
        }
    }
}
