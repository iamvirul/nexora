package me.virul.nexora.planner.llm;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.errors.AnthropicException;
import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.errors.InternalServerException;
import com.anthropic.errors.RateLimitException;
import com.anthropic.models.beta.AnthropicBeta;
import com.anthropic.models.beta.messages.BetaFallbacksParam;
import com.anthropic.models.beta.messages.BetaJsonOutputFormat;
import com.anthropic.models.beta.messages.BetaMessage;
import com.anthropic.models.beta.messages.BetaOutputConfig;
import com.anthropic.models.beta.messages.BetaStopReason;
import com.anthropic.models.beta.messages.MessageCreateParams;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Calls Claude through the official Anthropic Java SDK, asking for the plan as structured JSON
 * output ({@code output_config.format}), so the answer always parses against {@link PlanSchema}.
 *
 * <p>For models that support it, the request opts into server-side refusal fallback: if the model
 * declines, the API answers with a fallback model instead of a refusal. A refusal that still comes
 * back, or an answer cut off at {@code max_tokens}, fails the call and the planner falls back.
 *
 * <p>The SDK retries rate limits, overload and server errors itself ({@code maxRetries}).
 */
final class AnthropicPlanClient implements PlanCompletionClient {

    private static final Logger log = LoggerFactory.getLogger(AnthropicPlanClient.class);

    /** Models that accept {@code fallbacks: "default"}; others would reject the parameter. */
    private static final Set<String> REFUSAL_FALLBACK_MODELS =
            Set.of("claude-fable-5-1", "claude-opus-5-5", "claude-opus-5", "claude-sonnet-5-5");

    private static final int STATUS_RATE_LIMITED = 429;
    private static final int STATUS_SERVER_ERROR = 500;

    private final AnthropicClient client;
    private final LlmPlannerConfig config;
    private final boolean refusalFallback;

    AnthropicPlanClient(LlmPlannerConfig config) {
        this(buildClient(config), config);
    }

    AnthropicPlanClient(AnthropicClient client, LlmPlannerConfig config) {
        this.client = Objects.requireNonNull(client, "client must not be null");
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.refusalFallback = REFUSAL_FALLBACK_MODELS.contains(config.model());
    }

    private static AnthropicClient buildClient(LlmPlannerConfig config) {
        // fromEnv() also resolves ANTHROPIC_AUTH_TOKEN and saved profiles when no key was given.
        AnthropicOkHttpClient.Builder builder = AnthropicOkHttpClient.builder()
                .fromEnv()
                .timeout(config.timeout())
                .maxRetries(config.maxRetries());
        if (config.apiKey() != null) {
            builder.apiKey(config.apiKey());
        }
        if (config.baseUrl() != null) {
            builder.baseUrl(config.baseUrl());
        }
        return builder.build();
    }

    @Override
    public String complete(PlanPrompt prompt) {
        BetaMessage message;
        try {
            message = client.beta().messages().create(params(prompt));
        } catch (RateLimitException | InternalServerException e) {
            throw new LlmCallException(LlmCallException.Reason.UNAVAILABLE, describe(e), e);
        } catch (AnthropicServiceException e) {
            throw new LlmCallException(classify(e.statusCode()), describe(e), e);
        } catch (AnthropicIoException e) {
            throw new LlmCallException(
                    LlmCallException.Reason.NETWORK, "Anthropic API unreachable: " + e.getMessage(), e);
        } catch (AnthropicException e) {
            // Credentials could not be resolved, or the response could not be read.
            throw new LlmCallException(LlmCallException.Reason.REJECTED, "Anthropic SDK error: " + e.getMessage(), e);
        }
        return answerText(message);
    }

    MessageCreateParams params(PlanPrompt prompt) {
        BetaOutputConfig.Builder output = BetaOutputConfig.builder()
                .format(BetaJsonOutputFormat.builder()
                        .schema(schema(prompt.schema()))
                        .build());
        if (config.effort() != null) {
            output.effort(BetaOutputConfig.Effort.of(config.effort()));
        }
        MessageCreateParams.Builder params = MessageCreateParams.builder()
                .model(config.model())
                .maxTokens(config.maxTokens())
                .system(prompt.system())
                .outputConfig(output.build())
                .addUserMessage(prompt.user());
        if (refusalFallback) {
            params.addBeta(AnthropicBeta.SERVER_SIDE_FALLBACK_2026_07_01).fallbacks(BetaFallbacksParam.ofDefault());
        }
        return params.build();
    }

    private static BetaJsonOutputFormat.Schema schema(Map<String, Object> schema) {
        BetaJsonOutputFormat.Schema.Builder builder = BetaJsonOutputFormat.Schema.builder();
        schema.forEach((key, value) -> builder.putAdditionalProperty(key, JsonValue.from(value)));
        return builder.build();
    }

    private static String answerText(BetaMessage message) {
        BetaStopReason stop = message.stopReason().orElse(null);
        if (BetaStopReason.REFUSAL.equals(stop)) {
            throw new LlmCallException(LlmCallException.Reason.REFUSED, "the model declined to plan this goal");
        }
        if (BetaStopReason.MAX_TOKENS.equals(stop)) {
            throw new LlmCallException(
                    LlmCallException.Reason.TRUNCATED, "the answer hit max_tokens; raise NEXORA_LLM_MAX_TOKENS");
        }
        String text = message.content().stream()
                .flatMap(block -> block.text().stream())
                .map(block -> block.text())
                .collect(Collectors.joining());
        if (text.isBlank()) {
            throw new LlmCallException(
                    LlmCallException.Reason.EMPTY, "the response had no text (stop_reason=" + stop + ")");
        }
        return text;
    }

    private static LlmCallException.Reason classify(int status) {
        return status == STATUS_RATE_LIMITED || status >= STATUS_SERVER_ERROR
                ? LlmCallException.Reason.UNAVAILABLE
                : LlmCallException.Reason.REJECTED;
    }

    /** The status and the API's own message; the request body and key are never included. */
    private static String describe(AnthropicServiceException e) {
        return "Anthropic API returned HTTP " + e.statusCode() + ": " + e.getMessage();
    }

    @Override
    public void close() {
        try {
            client.close();
        } catch (RuntimeException e) {
            log.warn("Closing the Anthropic client failed", e);
        }
    }
}
