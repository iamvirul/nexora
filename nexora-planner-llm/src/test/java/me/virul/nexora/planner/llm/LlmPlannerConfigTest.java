package me.virul.nexora.planner.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LlmPlannerConfigTest {

    private final Map<String, String> properties = new HashMap<>();
    private final Map<String, String> env = new HashMap<>();

    @TempDir
    Path dir;

    @Test
    void offWhenNothingIsConfigured() {
        assertThat(load()).isEmpty();
    }

    @Test
    void anthropicKeyAloneSelectsAnthropicWithItsDefaultModel() {
        env.put("ANTHROPIC_API_KEY", "sk-ant-test");

        LlmPlannerConfig config = load().orElseThrow();

        assertThat(config.provider()).isEqualTo(LlmProvider.ANTHROPIC);
        assertThat(config.model()).isEqualTo("claude-opus-5-5");
        assertThat(config.apiKey()).isEqualTo("sk-ant-test");
        assertThat(config.maxTokens()).isEqualTo(LlmPlannerConfig.DEFAULT_MAX_TOKENS);
        assertThat(config.cacheTtl()).isEqualTo(LlmPlannerConfig.DEFAULT_CACHE_TTL);
    }

    @Test
    void anthropicWithoutKeyLeavesCredentialsToTheSdk() {
        env.put("NEXORA_LLM_PROVIDER", "anthropic");

        assertThat(load().orElseThrow().apiKey()).isNull();
    }

    @Test
    void openRouterUsesItsBaseUrlAndNeedsAModel() {
        env.put("OPENROUTER_API_KEY", "sk-or-test");
        assertThatThrownBy(this::load).hasMessageContaining("NEXORA_LLM_MODEL");

        env.put("NEXORA_LLM_MODEL", "meta-llama/llama-4-maverick");
        LlmPlannerConfig config = load().orElseThrow();

        assertThat(config.provider()).isEqualTo(LlmProvider.OPENROUTER);
        assertThat(config.baseUrl()).isEqualTo("https://openrouter.ai/api/v1");
    }

    @Test
    void geminiKeySelectsGeminiAndNeedsAModel() {
        env.put("GEMINI_API_KEY", "gm-test");
        assertThatThrownBy(this::load).hasMessageContaining("NEXORA_LLM_MODEL");

        env.put("NEXORA_LLM_MODEL", "some-gemini-model");
        LlmPlannerConfig config = load().orElseThrow();

        assertThat(config.provider()).isEqualTo(LlmProvider.GEMINI);
        assertThat(config.apiKey()).isEqualTo("gm-test");
        assertThat(config.baseUrl()).isNull();
    }

    @Test
    void geminiAcceptsTemperatureButNotEffort() {
        env.put("GEMINI_API_KEY", "gm-test");
        env.put("NEXORA_LLM_MODEL", "some-gemini-model");
        env.put("NEXORA_LLM_TEMPERATURE", "0.1");
        assertThat(load().orElseThrow().temperature()).isEqualTo(0.1);

        env.put("NEXORA_LLM_EFFORT", "low");
        assertThatThrownBy(this::load).hasMessageContaining("anthropic provider only");
    }

    @Test
    void openAiProviderWithoutKeyIsRejected() {
        env.put("NEXORA_LLM_PROVIDER", "openai");
        env.put("NEXORA_LLM_MODEL", "some-model");

        assertThatThrownBy(this::load).hasMessageContaining("OPENAI_API_KEY");
    }

    @Test
    void keyCanComeFromAFile() throws IOException {
        Path keyFile = Files.writeString(dir.resolve("key"), "  sk-from-file\n");
        env.put("NEXORA_LLM_PROVIDER", "openai");
        env.put("NEXORA_LLM_MODEL", "some-model");
        env.put("NEXORA_LLM_API_KEY_FILE", keyFile.toString());

        assertThat(load().orElseThrow().apiKey()).isEqualTo("sk-from-file");
    }

    @Test
    void systemPropertiesWinOverEnvironment() {
        env.put("ANTHROPIC_API_KEY", "sk-ant-test");
        env.put("NEXORA_LLM_MODEL", "claude-sonnet-5-5");
        properties.put("nexora.llm.model", "claude-haiku-5-5");
        properties.put("nexora.llm.cacheTtl", "PT30S");
        properties.put("nexora.llm.timeout", "15");

        LlmPlannerConfig config = load().orElseThrow();

        assertThat(config.model()).isEqualTo("claude-haiku-5-5");
        assertThat(config.cacheTtl()).isEqualTo(Duration.ofSeconds(30));
        assertThat(config.timeout()).isEqualTo(Duration.ofSeconds(15));
    }

    @Test
    void apiKeyIsNeverReadFromSystemProperties() {
        properties.put("nexora.llm.apiKey", "sk-leaked");
        env.put("NEXORA_LLM_PROVIDER", "openai");
        env.put("NEXORA_LLM_MODEL", "some-model");

        assertThatThrownBy(this::load).hasMessageContaining("OPENAI_API_KEY");
    }

    @Test
    void temperatureIsRefusedForAnthropic() {
        env.put("ANTHROPIC_API_KEY", "sk-ant-test");
        env.put("NEXORA_LLM_TEMPERATURE", "0.2");

        assertThatThrownBy(this::load).hasMessageContaining("effort");
    }

    @Test
    void temperatureIsAcceptedForOpenAi() {
        env.put("OPENAI_API_KEY", "sk-test");
        env.put("NEXORA_LLM_MODEL", "some-model");
        env.put("NEXORA_LLM_TEMPERATURE", "0.2");

        assertThat(load().orElseThrow().temperature()).isEqualTo(0.2);
    }

    @Test
    void nonFiniteTemperatureIsRefused() {
        env.put("OPENAI_API_KEY", "sk-test");
        env.put("NEXORA_LLM_MODEL", "some-model");
        env.put("NEXORA_LLM_TEMPERATURE", "NaN");
        assertThatThrownBy(this::load).hasMessageContaining("temperature must be between 0 and 2");

        env.put("NEXORA_LLM_TEMPERATURE", "Infinity");
        assertThatThrownBy(this::load).hasMessageContaining("temperature must be between 0 and 2");
    }

    @Test
    void effortIsRefusedOutsideAnthropic() {
        env.put("OPENAI_API_KEY", "sk-test");
        env.put("NEXORA_LLM_MODEL", "some-model");
        env.put("NEXORA_LLM_EFFORT", "high");

        assertThatThrownBy(this::load).hasMessageContaining("anthropic provider only");
    }

    @Test
    void unknownEffortIsRefused() {
        env.put("ANTHROPIC_API_KEY", "sk-ant-test");
        env.put("NEXORA_LLM_EFFORT", "extreme");

        assertThatThrownBy(this::load).hasMessageContaining("effort must be one of");
    }

    @Test
    void invalidNumbersAndDurationsAreRefused() {
        env.put("ANTHROPIC_API_KEY", "sk-ant-test");
        env.put("NEXORA_LLM_MAX_TOKENS", "lots");
        assertThatThrownBy(this::load).hasMessageContaining("maxTokens");

        env.remove("NEXORA_LLM_MAX_TOKENS");
        env.put("NEXORA_LLM_CACHE_TTL", "ten minutes");
        assertThatThrownBy(this::load).hasMessageContaining("cacheTtl");
    }

    @Test
    void nonHttpBaseUrlIsRefused() {
        env.put("ANTHROPIC_API_KEY", "sk-ant-test");
        env.put("NEXORA_LLM_BASE_URL", "file:///etc/passwd");

        assertThatThrownBy(this::load).hasMessageContaining("http(s)");
    }

    @Test
    void unknownProviderIsRefused() {
        env.put("NEXORA_LLM_PROVIDER", "skynet");

        assertThatThrownBy(this::load).hasMessageContaining("anthropic, openai, openrouter or gemini");
    }

    @Test
    void toStringNeverShowsTheKey() {
        env.put("ANTHROPIC_API_KEY", "sk-ant-secret-value");

        assertThat(load().orElseThrow().toString())
                .doesNotContain("sk-ant-secret-value")
                .contains("<redacted>");
    }

    private Optional<LlmPlannerConfig> load() {
        return LlmPlannerConfig.load(properties::get, env::get);
    }
}
