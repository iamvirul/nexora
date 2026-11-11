package me.virul.nexora.planner.llm;

import com.google.genai.Client;
import com.google.genai.errors.ApiException;
import com.google.genai.errors.GenAiIOException;
import com.google.genai.types.Candidate;
import com.google.genai.types.Content;
import com.google.genai.types.FinishReason;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.HttpOptions;
import com.google.genai.types.HttpRetryOptions;
import com.google.genai.types.Part;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Calls Gemini through Google's official Gen AI Java SDK on the Gemini Developer API, asking for the
 * plan as JSON constrained by {@link PlanSchema} ({@code responseJsonSchema}). Answers that still
 * stray from the schema are caught by {@link PlanParser}.
 *
 * <p>The SDK retries transient failures itself; {@code maxRetries} sets how many times.
 */
final class GeminiPlanClient implements PlanCompletionClient {

    private static final Logger log = LoggerFactory.getLogger(GeminiPlanClient.class);

    private static final String JSON_MIME_TYPE = "application/json";
    private static final int STATUS_RATE_LIMITED = 429;
    private static final int STATUS_SERVER_ERROR = 500;

    /** Finish reasons that mean the model or a safety filter withheld the answer. */
    private static final Set<FinishReason.Known> WITHHELD = Set.of(
            FinishReason.Known.SAFETY,
            FinishReason.Known.RECITATION,
            FinishReason.Known.BLOCKLIST,
            FinishReason.Known.PROHIBITED_CONTENT,
            FinishReason.Known.SPII);

    private final Client client;
    private final LlmPlannerConfig config;

    GeminiPlanClient(LlmPlannerConfig config) {
        this(buildClient(config), config);
    }

    GeminiPlanClient(Client client, LlmPlannerConfig config) {
        this.client = Objects.requireNonNull(client, "client must not be null");
        this.config = Objects.requireNonNull(config, "config must not be null");
    }

    private static Client buildClient(LlmPlannerConfig config) {
        HttpOptions.Builder http = HttpOptions.builder()
                .timeout(Math.toIntExact(config.timeout().toMillis()))
                // attempts counts the first try, so retries plus one.
                .retryOptions(HttpRetryOptions.builder().attempts(config.maxRetries() + 1));
        if (config.baseUrl() != null) {
            http.baseUrl(config.baseUrl());
        }
        return Client.builder()
                .apiKey(Objects.requireNonNull(config.apiKey(), "apiKey is required for gemini"))
                .httpOptions(http.build())
                .build();
    }

    @Override
    public String complete(PlanPrompt prompt) {
        GenerateContentResponse response;
        try {
            response = client.models.generateContent(config.model(), prompt.user(), generationConfig(prompt));
        } catch (ApiException e) {
            throw new LlmCallException(classify(e.code()), describe(e), e);
        } catch (GenAiIOException e) {
            throw new LlmCallException(LlmCallException.Reason.NETWORK, "Gemini API unreachable: " + e.getMessage(), e);
        }
        return answerText(response);
    }

    GenerateContentConfig generationConfig(PlanPrompt prompt) {
        GenerateContentConfig.Builder generation = GenerateContentConfig.builder()
                .systemInstruction(Content.fromParts(Part.fromText(prompt.system())))
                .responseMimeType(JSON_MIME_TYPE)
                .responseJsonSchema(prompt.schema())
                .maxOutputTokens(Math.toIntExact(config.maxTokens()));
        if (config.temperature() != null) {
            generation.temperature(config.temperature().floatValue());
        }
        return generation.build();
    }

    private static String answerText(GenerateContentResponse response) {
        Optional<String> blocked =
                response.promptFeedback().flatMap(f -> f.blockReason()).map(Object::toString);
        if (blocked.isPresent()) {
            throw new LlmCallException(
                    LlmCallException.Reason.REFUSED, "Gemini blocked the request (" + blocked.get() + ")");
        }
        List<Candidate> candidates = response.candidates().orElse(List.of());
        if (candidates.isEmpty()) {
            throw new LlmCallException(LlmCallException.Reason.EMPTY, "the response had no candidates");
        }
        FinishReason.Known finish = candidates
                .get(0)
                .finishReason()
                .map(FinishReason::knownEnum)
                .orElse(FinishReason.Known.FINISH_REASON_UNSPECIFIED);
        if (WITHHELD.contains(finish)) {
            throw new LlmCallException(LlmCallException.Reason.REFUSED, "Gemini withheld the answer (" + finish + ")");
        }
        if (finish == FinishReason.Known.MAX_TOKENS) {
            throw new LlmCallException(
                    LlmCallException.Reason.TRUNCATED, "the answer hit the token limit; raise NEXORA_LLM_MAX_TOKENS");
        }
        String text = response.text();
        if (text == null || text.isBlank()) {
            throw new LlmCallException(
                    LlmCallException.Reason.EMPTY, "the response had no text (finishReason=" + finish + ")");
        }
        return text;
    }

    private static LlmCallException.Reason classify(int status) {
        return status == STATUS_RATE_LIMITED || status >= STATUS_SERVER_ERROR
                ? LlmCallException.Reason.UNAVAILABLE
                : LlmCallException.Reason.REJECTED;
    }

    /** The status and the API's own message; the request body and key are never included. */
    private static String describe(ApiException e) {
        return "Gemini API returned HTTP " + e.code() + " " + e.status() + ": " + e.message();
    }

    @Override
    public void close() {
        try {
            client.close();
        } catch (RuntimeException e) {
            log.warn("Closing the Gemini client failed", e);
        }
    }
}
