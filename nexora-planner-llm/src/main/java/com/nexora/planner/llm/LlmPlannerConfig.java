package com.nexora.planner.llm;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * Settings for the LLM planner, validated once at plugin start.
 *
 * <p>Each setting is read from the system property {@code nexora.llm.<name>}, then the environment
 * variable {@code NEXORA_LLM_<NAME>}, for example {@code nexora.llm.model} / {@code NEXORA_LLM_MODEL}.
 * API keys are never read from system properties, which other processes can see: they come from the
 * provider's environment variable ({@code ANTHROPIC_API_KEY}, {@code OPENAI_API_KEY},
 * {@code OPENROUTER_API_KEY}, {@code GEMINI_API_KEY}) or from the file named by {@code apiKeyFile}.
 *
 * @param apiKey      null for Anthropic when the SDK should resolve credentials itself
 *                    ({@code ANTHROPIC_AUTH_TOKEN}, an {@code ant auth login} profile)
 * @param temperature null to leave it to the provider; refused for Anthropic, whose current
 *                    models reject it
 * @param effort      Anthropic only; null leaves the model's default
 * @param baseUrl     null for the provider's default endpoint
 * @param cacheTtl    {@link Duration#ZERO} disables the plan cache
 */
public record LlmPlannerConfig(
        LlmProvider provider,
        String apiKey,
        String model,
        long maxTokens,
        Double temperature,
        String effort,
        String baseUrl,
        Duration timeout,
        int maxRetries,
        Duration cacheTtl,
        int cacheMaxEntries,
        int priority) {

    public static final long DEFAULT_MAX_TOKENS = 16_000;
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(60);
    public static final int DEFAULT_MAX_RETRIES = 2;
    public static final Duration DEFAULT_CACHE_TTL = Duration.ofMinutes(10);
    public static final int DEFAULT_CACHE_MAX_ENTRIES = 1_000;
    /** Above the default plugin planner priority (0), so the LLM planner is tried first. */
    public static final int DEFAULT_PRIORITY = 100;

    private static final Set<String> EFFORTS = Set.of("low", "medium", "high", "xhigh", "max");
    private static final double MAX_TEMPERATURE = 2.0;
    private static final String PROPERTY_PREFIX = "nexora.llm.";
    private static final String ENV_PREFIX = "NEXORA_LLM_";
    private static final String ANTHROPIC_AUTH_TOKEN = "ANTHROPIC_AUTH_TOKEN";

    public LlmPlannerConfig {
        Objects.requireNonNull(provider, "provider must not be null");
        Objects.requireNonNull(model, "model must not be null");
        Objects.requireNonNull(timeout, "timeout must not be null");
        Objects.requireNonNull(cacheTtl, "cacheTtl must not be null");
        if (model.isBlank()) {
            throw new IllegalArgumentException("model must not be blank");
        }
        if (maxTokens < 1) {
            throw new IllegalArgumentException("maxTokens must be positive, got " + maxTokens);
        }
        if (temperature != null) {
            if (provider == LlmProvider.ANTHROPIC) {
                throw new IllegalArgumentException("temperature is not supported by current Claude models;"
                        + " set effort (low, medium, high, xhigh, max) instead");
            }
            // NaN fails both comparisons below, so reject non-finite values first.
            if (!Double.isFinite(temperature) || temperature < 0 || temperature > MAX_TEMPERATURE) {
                throw new IllegalArgumentException("temperature must be between 0 and 2, got " + temperature);
            }
        }
        if (effort != null) {
            if (provider != LlmProvider.ANTHROPIC) {
                throw new IllegalArgumentException("effort applies to the anthropic provider only");
            }
            if (!EFFORTS.contains(effort)) {
                throw new IllegalArgumentException(
                        "effort must be one of low, medium, high, xhigh, max, got '" + effort + "'");
            }
        }
        if (baseUrl != null) {
            requireHttpUrl(baseUrl);
        }
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("timeout must be positive, got " + timeout);
        }
        if (maxRetries < 0) {
            throw new IllegalArgumentException("maxRetries must not be negative, got " + maxRetries);
        }
        if (cacheTtl.isNegative()) {
            throw new IllegalArgumentException("cacheTtl must not be negative, got " + cacheTtl);
        }
        if (cacheMaxEntries < 1) {
            throw new IllegalArgumentException("cacheMaxEntries must be positive, got " + cacheMaxEntries);
        }
    }

    /** The settings from system properties and the environment. */
    public static Optional<LlmPlannerConfig> fromEnvironment() {
        return load(System::getProperty, System::getenv);
    }

    /**
     * @param properties looks up {@code nexora.llm.*} names, such as {@link System#getProperty}
     * @param env        looks up environment variables, such as {@link System#getenv}
     * @return empty when no provider is chosen and no provider API key is set, so the planner is off
     * @throws IllegalArgumentException when a setting is present but invalid
     */
    public static Optional<LlmPlannerConfig> load(Function<String, String> properties, Function<String, String> env) {
        Settings settings = new Settings(properties, env);
        Optional<LlmProvider> chosen = settings.get("provider").map(LlmProvider::parse);
        Optional<LlmProvider> provider = chosen.or(() -> detectProvider(env));
        if (provider.isEmpty()) {
            return Optional.empty();
        }
        LlmProvider selected = provider.get();
        String apiKey = apiKey(selected, settings, env);
        String model = settings.get("model")
                .or(selected::defaultModel)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Set NEXORA_LLM_MODEL (or nexora.llm.model) to the " + name(selected) + " model to plan with"));
        return Optional.of(new LlmPlannerConfig(
                selected,
                apiKey,
                model,
                settings.get("maxTokens").map(v -> parseLong("maxTokens", v)).orElse(DEFAULT_MAX_TOKENS),
                settings.get("temperature")
                        .map(v -> parseDouble("temperature", v))
                        .orElse(null),
                settings.get("effort").map(v -> v.toLowerCase(Locale.ROOT)).orElse(null),
                settings.get("baseUrl").or(selected::defaultBaseUrl).orElse(null),
                settings.get("timeout").map(v -> parseDuration("timeout", v)).orElse(DEFAULT_TIMEOUT),
                settings.get("maxRetries").map(v -> parseInt("maxRetries", v)).orElse(DEFAULT_MAX_RETRIES),
                settings.get("cacheTtl").map(v -> parseDuration("cacheTtl", v)).orElse(DEFAULT_CACHE_TTL),
                settings.get("cacheMaxEntries")
                        .map(v -> parseInt("cacheMaxEntries", v))
                        .orElse(DEFAULT_CACHE_MAX_ENTRIES),
                settings.get("priority").map(v -> parseInt("priority", v)).orElse(DEFAULT_PRIORITY)));
    }

    /** The first provider, in declaration order, whose credentials are in the environment. */
    private static Optional<LlmProvider> detectProvider(Function<String, String> env) {
        if (present(env.apply(ANTHROPIC_AUTH_TOKEN))) {
            return Optional.of(LlmProvider.ANTHROPIC);
        }
        for (LlmProvider candidate : LlmProvider.values()) {
            if (present(env.apply(candidate.apiKeyEnvVar()))) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    private static String apiKey(LlmProvider provider, Settings settings, Function<String, String> env) {
        String fromEnv = env.apply(provider.apiKeyEnvVar());
        if (present(fromEnv)) {
            return fromEnv.strip();
        }
        Optional<String> keyFile = settings.get("apiKeyFile");
        if (keyFile.isPresent()) {
            return readKeyFile(Path.of(keyFile.get()));
        }
        if (provider == LlmProvider.ANTHROPIC) {
            return null; // the Anthropic SDK resolves ANTHROPIC_AUTH_TOKEN and saved profiles itself
        }
        throw new IllegalArgumentException("Set " + provider.apiKeyEnvVar() + " or NEXORA_LLM_API_KEY_FILE to use the "
                + name(provider) + " provider");
    }

    private static String readKeyFile(Path file) {
        try {
            String key = Files.readString(file, StandardCharsets.UTF_8).strip();
            if (key.isEmpty()) {
                throw new IllegalArgumentException("API key file is empty: " + file);
            }
            return key;
        } catch (IOException e) {
            throw new IllegalArgumentException("Cannot read the API key file " + file + ": " + e.getMessage(), e);
        }
    }

    private static void requireHttpUrl(String url) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("baseUrl must be an http(s) URL, got '" + url + "'", e);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!Set.of("http", "https").contains(scheme) || uri.getHost() == null) {
            throw new IllegalArgumentException("baseUrl must be an http(s) URL, got '" + url + "'");
        }
    }

    /** ISO-8601 ({@code PT30S}) or a whole number of seconds. */
    static Duration parseDuration(String name, String value) {
        String trimmed = value.strip();
        try {
            return trimmed.chars().allMatch(Character::isDigit) && !trimmed.isEmpty()
                    ? Duration.ofSeconds(Long.parseLong(trimmed))
                    : Duration.parse(trimmed);
        } catch (NumberFormatException | DateTimeParseException e) {
            throw new IllegalArgumentException(
                    name + " must be seconds or an ISO-8601 duration such as PT30S, got '" + value + "'", e);
        }
    }

    private static long parseLong(String name, String value) {
        try {
            return Long.parseLong(value.strip());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " must be a whole number, got '" + value + "'", e);
        }
    }

    private static int parseInt(String name, String value) {
        try {
            return Integer.parseInt(value.strip());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " must be a whole number, got '" + value + "'", e);
        }
    }

    private static double parseDouble(String name, String value) {
        try {
            return Double.parseDouble(value.strip());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " must be a number, got '" + value + "'", e);
        }
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }

    private static String name(LlmProvider provider) {
        return provider.name().toLowerCase(Locale.ROOT);
    }

    /** Never prints the API key. */
    @Override
    public String toString() {
        return "LlmPlannerConfig[provider=" + name(provider) + ", model=" + model + ", apiKey="
                + (apiKey == null ? "<sdk default>" : "<redacted>") + ", maxTokens=" + maxTokens
                + ", temperature=" + temperature + ", effort=" + effort + ", baseUrl=" + baseUrl + ", timeout="
                + timeout + ", maxRetries=" + maxRetries + ", cacheTtl=" + cacheTtl + ", cacheMaxEntries="
                + cacheMaxEntries + ", priority=" + priority + "]";
    }

    /** One setting: the {@code nexora.llm.} property, else the {@code NEXORA_LLM_} variable. */
    private record Settings(Function<String, String> properties, Function<String, String> env) {
        Optional<String> get(String name) {
            String property = properties.apply(PROPERTY_PREFIX + name);
            if (present(property)) {
                return Optional.of(property.strip());
            }
            String variable = env.apply(ENV_PREFIX + screamingSnake(name));
            return present(variable) ? Optional.of(variable.strip()) : Optional.empty();
        }

        private static String screamingSnake(String camel) {
            return camel.replaceAll("([a-z])([A-Z])", "$1_$2").toUpperCase(Locale.ROOT);
        }
    }
}
