package me.virul.nexora.planner.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The real Anthropic and OpenAI SDK clients against a local fake API: no network, no key. */
class ProviderClientsTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private FakeLlmServer server;
    private final PlanPrompt prompt = PlanPromptBuilder.build(Fixtures.GOAL, Fixtures.CAPABILITIES, List.of("orderId"));

    @BeforeEach
    void start() throws IOException {
        server = new FakeLlmServer();
    }

    @AfterEach
    void stop() {
        server.close();
    }

    @Test
    void anthropicSendsStructuredOutputRequestAndReturnsText() {
        server.anthropicAnswer(Fixtures.VALID_PLAN, "end_turn");

        try (AnthropicPlanClient client = anthropic("claude-opus-5-5")) {
            assertThat(client.complete(prompt)).isEqualTo(Fixtures.VALID_PLAN);
        }

        FakeLlmServer.Recorded request = server.lastRequest();
        assertThat(request.path()).isEqualTo(FakeLlmServer.ANTHROPIC_PATH);
        JsonNode body = request.body();
        assertThat(body.path("model").asText()).isEqualTo("claude-opus-5-5");
        assertThat(body.path("max_tokens").asLong()).isEqualTo(LlmPlannerConfig.DEFAULT_MAX_TOKENS);
        assertThat(body.path("system").toString()).contains("You plan workflows");
        assertThat(body.path("output_config").path("format").path("type").asText())
                .isEqualTo("json_schema");
        assertThat(body.path("output_config").path("format").path("schema"))
                .isEqualTo(JSON.valueToTree(PlanSchema.schema()));
        assertThat(body.has("temperature")).isFalse();
        // anthropic-java 3.x sends the API key as a Bearer token.
        assertThat(request.headers().get("Authorization")).containsExactly("Bearer test-key");
    }

    @Test
    void anthropicOptsIntoRefusalFallbackOnSupportedModels() {
        server.anthropicAnswer(Fixtures.VALID_PLAN, "end_turn");

        try (AnthropicPlanClient client = anthropic("claude-opus-5-5")) {
            client.complete(prompt);
        }

        FakeLlmServer.Recorded request = server.lastRequest();
        assertThat(request.body().path("fallbacks").asText()).isEqualTo("default");
        assertThat(String.join(",", request.headers().get("Anthropic-beta")))
                .contains("server-side-fallback-2026-07-01");
    }

    @Test
    void anthropicLeavesFallbackOffForOtherModels() {
        server.anthropicAnswer(Fixtures.VALID_PLAN, "end_turn");

        try (AnthropicPlanClient client = anthropic("claude-haiku-5-5")) {
            client.complete(prompt);
        }

        assertThat(server.lastRequest().body().has("fallbacks")).isFalse();
    }

    @Test
    void anthropicSendsEffortWhenConfigured() {
        server.anthropicAnswer(Fixtures.VALID_PLAN, "end_turn");
        LlmPlannerConfig config =
                withEffort(Fixtures.config(LlmProvider.ANTHROPIC, "claude-opus-5-5", server.baseUrl()));

        try (AnthropicPlanClient client = new AnthropicPlanClient(config)) {
            client.complete(prompt);
        }

        assertThat(server.lastRequest()
                        .body()
                        .path("output_config")
                        .path("effort")
                        .asText())
                .isEqualTo("low");
    }

    @Test
    void anthropicRefusalFailsAsRefused() {
        server.anthropicAnswer("", "refusal");

        assertReason(anthropic("claude-opus-5-5"), LlmCallException.Reason.REFUSED);
    }

    @Test
    void anthropicMaxTokensFailsAsTruncated() {
        server.anthropicAnswer("{\"steps\": [", "max_tokens");

        assertReason(anthropic("claude-opus-5-5"), LlmCallException.Reason.TRUNCATED);
    }

    @Test
    void anthropicBadRequestFailsAsRejected() {
        server.reply(400, FakeLlmServer.anthropicError("invalid_request_error", "bad model"));

        assertReason(anthropic("claude-opus-5-5"), LlmCallException.Reason.REJECTED);
    }

    @Test
    void anthropicRateLimitIsRetriedThenFailsAsUnavailable() {
        server.reply(429, FakeLlmServer.anthropicError("rate_limit_error", "slow down"));
        server.reply(429, FakeLlmServer.anthropicError("rate_limit_error", "slow down"));
        LlmPlannerConfig config =
                withRetries(Fixtures.config(LlmProvider.ANTHROPIC, "claude-opus-5-5", server.baseUrl()), 1);

        assertReason(new AnthropicPlanClient(config), LlmCallException.Reason.UNAVAILABLE);
        assertThat(server.requests()).hasSize(2);
    }

    @Test
    void anthropicRateLimitThenSuccessIsRetried() {
        server.reply(429, FakeLlmServer.anthropicError("rate_limit_error", "slow down"));
        server.anthropicAnswer(Fixtures.VALID_PLAN, "end_turn");
        LlmPlannerConfig config =
                withRetries(Fixtures.config(LlmProvider.ANTHROPIC, "claude-opus-5-5", server.baseUrl()), 1);

        try (AnthropicPlanClient client = new AnthropicPlanClient(config)) {
            assertThat(client.complete(prompt)).isEqualTo(Fixtures.VALID_PLAN);
        }
    }

    @Test
    void anthropicUnreachableFailsAsNetwork() {
        String deadUrl = server.baseUrl();
        server.close();

        assertReason(
                new AnthropicPlanClient(Fixtures.config(LlmProvider.ANTHROPIC, "claude-opus-5-5", deadUrl)),
                LlmCallException.Reason.NETWORK);
    }

    @Test
    void openAiSendsStrictJsonSchemaAndReturnsContent() {
        server.openAiAnswer(Fixtures.VALID_PLAN, "stop", null);

        try (OpenAiPlanClient client = openAi(LlmProvider.OPENAI, server.baseUrl() + "/v1")) {
            assertThat(client.complete(prompt)).isEqualTo(Fixtures.VALID_PLAN);
        }

        FakeLlmServer.Recorded request = server.lastRequest();
        assertThat(request.path()).isEqualTo(FakeLlmServer.OPENAI_PATH);
        JsonNode format = request.body().path("response_format");
        assertThat(format.path("type").asText()).isEqualTo("json_schema");
        assertThat(format.path("json_schema").path("strict").asBoolean()).isTrue();
        assertThat(format.path("json_schema").path("name").asText()).isEqualTo(PlanSchema.NAME);
        assertThat(format.path("json_schema").path("schema")).isEqualTo(JSON.valueToTree(PlanSchema.schema()));
        assertThat(request.body().path("max_completion_tokens").asLong())
                .isEqualTo(LlmPlannerConfig.DEFAULT_MAX_TOKENS);
        assertThat(request.headers().get("Authorization")).containsExactly("Bearer test-key");
    }

    @Test
    void openRouterUsesItsPathAndMaxTokens() {
        server.openAiAnswer(Fixtures.VALID_PLAN, "stop", null);

        try (OpenAiPlanClient client = openAi(LlmProvider.OPENROUTER, server.openRouterBaseUrl())) {
            assertThat(client.complete(prompt)).isEqualTo(Fixtures.VALID_PLAN);
        }

        assertThat(server.lastRequest().path()).isEqualTo(FakeLlmServer.OPENROUTER_PATH);
        assertThat(server.lastRequest().body().path("max_tokens").asLong())
                .isEqualTo(LlmPlannerConfig.DEFAULT_MAX_TOKENS);
    }

    @Test
    void openAiRefusalFailsAsRefused() {
        server.openAiAnswer(null, "stop", "I can't help with that.");

        assertReason(openAi(LlmProvider.OPENAI, server.baseUrl() + "/v1"), LlmCallException.Reason.REFUSED);
    }

    @Test
    void openAiLengthFailsAsTruncated() {
        server.openAiAnswer("{\"steps\": [", "length", null);

        assertReason(openAi(LlmProvider.OPENAI, server.baseUrl() + "/v1"), LlmCallException.Reason.TRUNCATED);
    }

    @Test
    void openAiUnauthorizedFailsAsRejected() {
        server.reply(401, FakeLlmServer.openAiError("bad key"));

        assertReason(openAi(LlmProvider.OPENAI, server.baseUrl() + "/v1"), LlmCallException.Reason.REJECTED);
    }

    @Test
    void openAiServerErrorFailsAsUnavailable() {
        server.reply(503, FakeLlmServer.openAiError("down"));

        assertReason(openAi(LlmProvider.OPENAI, server.baseUrl() + "/v1"), LlmCallException.Reason.UNAVAILABLE);
    }

    @Test
    void geminiSendsJsonSchemaRequestAndReturnsText() {
        server.geminiAnswer(Fixtures.VALID_PLAN, "STOP");

        try (GeminiPlanClient client = gemini()) {
            assertThat(client.complete(prompt)).isEqualTo(Fixtures.VALID_PLAN);
        }

        FakeLlmServer.Recorded request = server.lastRequest();
        assertThat(request.path()).endsWith("/models/test-gemini:generateContent");
        JsonNode generation = request.body().path("generationConfig");
        assertThat(generation.path("responseMimeType").asText()).isEqualTo("application/json");
        assertThat(generation.path("responseJsonSchema")).isEqualTo(JSON.valueToTree(PlanSchema.schema()));
        assertThat(generation.path("maxOutputTokens").asLong()).isEqualTo(LlmPlannerConfig.DEFAULT_MAX_TOKENS);
        assertThat(request.body().path("systemInstruction").toString()).contains("You plan workflows");
        assertThat(request.body().path("contents").toString()).contains("charge_card");
        assertThat(request.headers().get("X-goog-api-key")).containsExactly("test-key");
    }

    @Test
    void geminiMaxTokensFailsAsTruncated() {
        server.geminiAnswer("{\"steps\": [", "MAX_TOKENS");

        assertReason(gemini(), LlmCallException.Reason.TRUNCATED);
    }

    @Test
    void geminiSafetyStopFailsAsRefused() {
        server.geminiAnswer("", "SAFETY");

        assertReason(gemini(), LlmCallException.Reason.REFUSED);
    }

    @Test
    void geminiBlockedPromptFailsAsRefused() {
        server.reply(200, FakeLlmServer.geminiBlocked("PROHIBITED_CONTENT"));

        assertReason(gemini(), LlmCallException.Reason.REFUSED);
    }

    @Test
    void geminiBadRequestFailsAsRejected() {
        server.reply(400, FakeLlmServer.geminiError(400, "INVALID_ARGUMENT", "bad schema"));

        assertReason(gemini(), LlmCallException.Reason.REJECTED);
    }

    @Test
    void geminiServerErrorFailsAsUnavailable() {
        server.reply(503, FakeLlmServer.geminiError(503, "UNAVAILABLE", "overloaded"));

        assertReason(gemini(), LlmCallException.Reason.UNAVAILABLE);
    }

    @Test
    void geminiUnreachableFailsAsNetwork() {
        String deadUrl = server.baseUrl();
        server.close();

        assertReason(
                new GeminiPlanClient(Fixtures.config(LlmProvider.GEMINI, "test-gemini", deadUrl)),
                LlmCallException.Reason.NETWORK);
    }

    private GeminiPlanClient gemini() {
        return new GeminiPlanClient(Fixtures.config(LlmProvider.GEMINI, "test-gemini", server.baseUrl()));
    }

    private AnthropicPlanClient anthropic(String model) {
        return new AnthropicPlanClient(Fixtures.config(LlmProvider.ANTHROPIC, model, server.baseUrl()));
    }

    private OpenAiPlanClient openAi(LlmProvider provider, String baseUrl) {
        return new OpenAiPlanClient(Fixtures.config(provider, "test-model", baseUrl));
    }

    private void assertReason(PlanCompletionClient client, LlmCallException.Reason reason) {
        try (client) {
            assertThatThrownBy(() -> client.complete(prompt))
                    .isInstanceOfSatisfying(
                            LlmCallException.class, e -> assertThat(e.reason()).isEqualTo(reason));
        }
    }

    private static LlmPlannerConfig withEffort(LlmPlannerConfig c) {
        return new LlmPlannerConfig(
                c.provider(),
                c.apiKey(),
                c.model(),
                c.maxTokens(),
                null,
                "low",
                c.baseUrl(),
                c.timeout(),
                c.maxRetries(),
                c.cacheTtl(),
                c.cacheMaxEntries(),
                c.priority());
    }

    private static LlmPlannerConfig withRetries(LlmPlannerConfig c, int retries) {
        return new LlmPlannerConfig(
                c.provider(),
                c.apiKey(),
                c.model(),
                c.maxTokens(),
                c.temperature(),
                c.effort(),
                c.baseUrl(),
                c.timeout(),
                retries,
                c.cacheTtl(),
                c.cacheMaxEntries(),
                c.priority());
    }
}
