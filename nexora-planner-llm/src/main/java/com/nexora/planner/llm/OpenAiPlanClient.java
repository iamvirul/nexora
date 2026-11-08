package com.nexora.planner.llm;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.JsonValue;
import com.openai.errors.OpenAIException;
import com.openai.errors.OpenAIIoException;
import com.openai.errors.OpenAIServiceException;
import com.openai.errors.RateLimitException;
import com.openai.models.ResponseFormatJsonSchema;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.chat.completions.ChatCompletionMessage;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Calls the OpenAI Chat Completions API through the official OpenAI Java SDK, asking for the plan
 * as a strict JSON schema response. OpenRouter serves the same API, so the {@code openrouter}
 * provider uses this client pointed at OpenRouter's base URL, which reaches every model OpenRouter
 * routes to. Models that ignore the schema are still caught by {@link PlanParser}.
 *
 * <p>The SDK retries rate limits and server errors itself ({@code maxRetries}).
 */
final class OpenAiPlanClient implements PlanCompletionClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiPlanClient.class);

    private static final int STATUS_RATE_LIMITED = 429;
    private static final int STATUS_SERVER_ERROR = 500;

    private final OpenAIClient client;
    private final LlmPlannerConfig config;

    OpenAiPlanClient(LlmPlannerConfig config) {
        this(buildClient(config), config);
    }

    OpenAiPlanClient(OpenAIClient client, LlmPlannerConfig config) {
        this.client = Objects.requireNonNull(client, "client must not be null");
        this.config = Objects.requireNonNull(config, "config must not be null");
    }

    private static OpenAIClient buildClient(LlmPlannerConfig config) {
        OpenAIOkHttpClient.Builder builder = OpenAIOkHttpClient.builder()
                .apiKey(Objects.requireNonNull(config.apiKey(), "apiKey is required for " + config.provider()))
                .timeout(config.timeout())
                .maxRetries(config.maxRetries());
        if (config.baseUrl() != null) {
            builder.baseUrl(config.baseUrl());
        }
        return builder.build();
    }

    @Override
    public String complete(PlanPrompt prompt) {
        ChatCompletion completion;
        try {
            completion = client.chat().completions().create(params(prompt));
        } catch (RateLimitException e) {
            throw new LlmCallException(LlmCallException.Reason.UNAVAILABLE, describe(e), e);
        } catch (OpenAIServiceException e) {
            throw new LlmCallException(classify(e.statusCode()), describe(e), e);
        } catch (OpenAIIoException e) {
            throw new LlmCallException(
                    LlmCallException.Reason.NETWORK, providerName() + " API unreachable: " + e.getMessage(), e);
        } catch (OpenAIException e) {
            throw new LlmCallException(
                    LlmCallException.Reason.REJECTED, providerName() + " SDK error: " + e.getMessage(), e);
        }
        return answerText(completion);
    }

    ChatCompletionCreateParams params(PlanPrompt prompt) {
        ChatCompletionCreateParams.Builder params = ChatCompletionCreateParams.builder()
                .model(config.model())
                .addSystemMessage(prompt.system())
                .addUserMessage(prompt.user())
                .responseFormat(ResponseFormatJsonSchema.builder()
                        .jsonSchema(ResponseFormatJsonSchema.JsonSchema.builder()
                                .name(PlanSchema.NAME)
                                .strict(true)
                                .schema(schema(prompt.schema()))
                                .build())
                        .build());
        // OpenAI replaced max_tokens with max_completion_tokens; OpenRouter documents max_tokens.
        if (config.provider() == LlmProvider.OPENROUTER) {
            params.maxTokens(config.maxTokens());
        } else {
            params.maxCompletionTokens(config.maxTokens());
        }
        if (config.temperature() != null) {
            params.temperature(config.temperature());
        }
        return params.build();
    }

    private static ResponseFormatJsonSchema.JsonSchema.Schema schema(Map<String, Object> schema) {
        ResponseFormatJsonSchema.JsonSchema.Schema.Builder builder =
                ResponseFormatJsonSchema.JsonSchema.Schema.builder();
        schema.forEach((key, value) -> builder.putAdditionalProperty(key, JsonValue.from(value)));
        return builder.build();
    }

    private String answerText(ChatCompletion completion) {
        if (completion.choices().isEmpty()) {
            throw new LlmCallException(LlmCallException.Reason.EMPTY, "the response had no choices");
        }
        ChatCompletion.Choice choice = completion.choices().get(0);
        ChatCompletionMessage message = choice.message();
        if (message.refusal().filter(r -> !r.isBlank()).isPresent()
                || ChatCompletion.Choice.FinishReason.CONTENT_FILTER.equals(choice.finishReason())) {
            throw new LlmCallException(LlmCallException.Reason.REFUSED, "the model declined to plan this goal");
        }
        if (ChatCompletion.Choice.FinishReason.LENGTH.equals(choice.finishReason())) {
            throw new LlmCallException(
                    LlmCallException.Reason.TRUNCATED, "the answer hit the token limit; raise NEXORA_LLM_MAX_TOKENS");
        }
        String text = message.content().orElse("");
        if (text.isBlank()) {
            throw new LlmCallException(LlmCallException.Reason.EMPTY, "the response had no text");
        }
        return text;
    }

    private static LlmCallException.Reason classify(int status) {
        return status == STATUS_RATE_LIMITED || status >= STATUS_SERVER_ERROR
                ? LlmCallException.Reason.UNAVAILABLE
                : LlmCallException.Reason.REJECTED;
    }

    /** The status and the API's own message; the request body and key are never included. */
    private String describe(OpenAIServiceException e) {
        return providerName() + " API returned HTTP " + e.statusCode() + ": " + e.getMessage();
    }

    private String providerName() {
        return config.provider() == LlmProvider.OPENROUTER ? "OpenRouter" : "OpenAI";
    }

    @Override
    public void close() {
        try {
            client.close();
        } catch (RuntimeException e) {
            log.warn("Closing the {} client failed", providerName(), e);
        }
    }
}
