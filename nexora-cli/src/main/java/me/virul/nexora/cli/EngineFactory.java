package me.virul.nexora.cli;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.function.UnaryOperator;
import me.virul.nexora.api.NexoraEngine;
import me.virul.nexora.persistence.jdbc.JdbcExecutionStore;
import me.virul.nexora.planner.model.StepDefinition;
import me.virul.nexora.retry.ExponentialBackoffPolicy;
import me.virul.nexora.tracing.otel.OtelTracer;

final class EngineFactory {

    private EngineFactory() {}

    static NexoraEngine fromConfig(CliConfig config) {
        return fromConfig(config, System::getenv);
    }

    /** @param env reads environment variables; tests pass their own */
    static NexoraEngine fromConfig(CliConfig config, UnaryOperator<String> env) {
        String secret = config.webhookSecret != null && !config.webhookSecret.isBlank()
                ? config.webhookSecret
                : env.apply("NEXORA_WEBHOOK_SECRET");

        NexoraEngine.Builder builder =
                NexoraEngine.builder().withWebhookSecret(secret).withStepOutputMaxBytes(config.stepOutputMaxBytes());

        switch (StoreSelection.resolve(config, env)) {
            case StoreSelection.JdbcUrl jdbc ->
                builder.withExecutionStore(new JdbcExecutionStore(jdbc.url(), config.poolConfig()));
            case StoreSelection.H2File file ->
                builder.withExecutionStore(JdbcExecutionStore.h2AutoServer(file.path(), config.poolConfig()));
            case StoreSelection.None() -> {
                // In-memory only: nothing to open.
            }
        }

        String otlpEndpoint = config.otel.endpoint != null && !config.otel.endpoint.isBlank()
                ? config.otel.endpoint
                : env.apply("OTEL_EXPORTER_OTLP_ENDPOINT");
        if (otlpEndpoint != null && !otlpEndpoint.isBlank()) {
            builder.withTracer(OtelTracer.forEndpoint(otlpEndpoint));
        }

        builder.withDefaultRetryPolicy(ExponentialBackoffPolicy.builder()
                .maxAttempts(config.retry.maxAttempts)
                .initialDelay(Duration.ofMillis(config.retry.initialDelayMs))
                .multiplier(config.retry.multiplier)
                .maxDelay(Duration.ofMillis(config.retry.maxDelayMs))
                .build());

        for (String jar : config.plugins == null ? List.<String>of() : config.plugins) {
            if (jar == null || jar.isBlank()) {
                throw new IllegalArgumentException("plugins in nexora.json must not contain a blank path");
            }
            builder.withPluginJar(Path.of(jar).toAbsolutePath().normalize());
        }

        for (CliConfig.StepConfig sc : config.steps) {
            String match = sc.matchesGoalContains;
            StepDefinition.Builder sdb = StepDefinition.builder(sc.id, sc.capabilityId)
                    .withMatcher(goal -> match == null || goal.contains(match));
            if (sc.condition != null) {
                sdb.withCondition(sc.condition.toStepCondition());
            }
            builder.withStepDefinition(sdb.build());
        }

        return builder.build();
    }
}
