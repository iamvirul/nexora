package com.nexora.cli;

import com.nexora.api.NexoraEngine;
import com.nexora.persistence.jdbc.JdbcExecutionStore;
import com.nexora.planner.model.StepDefinition;
import com.nexora.retry.ExponentialBackoffPolicy;
import com.nexora.tracing.otel.OtelTracer;

import java.time.Duration;

final class EngineFactory {

    private EngineFactory() {}

    static NexoraEngine fromConfig(CliConfig config) {
        String secret = config.webhookSecret != null && !config.webhookSecret.isBlank() 
                ? config.webhookSecret 
                : System.getenv("NEXORA_WEBHOOK_SECRET");

        NexoraEngine.Builder builder = NexoraEngine.builder()
                .withWebhookSecret(secret);

        if (config.executionStore != null && !config.executionStore.isBlank()) {
            builder.withExecutionStore(JdbcExecutionStore.h2AutoServer(config.executionStore));
        }

        String otlpEndpoint = config.otel.endpoint != null && !config.otel.endpoint.isBlank()
                ? config.otel.endpoint
                : System.getenv("OTEL_EXPORTER_OTLP_ENDPOINT");
        if (otlpEndpoint != null && !otlpEndpoint.isBlank()) {
            builder.withTracer(OtelTracer.forEndpoint(otlpEndpoint));
        }

        builder.withDefaultRetryPolicy(
                        ExponentialBackoffPolicy.builder()
                                .maxAttempts(config.retry.maxAttempts)
                                .initialDelay(Duration.ofMillis(config.retry.initialDelayMs))
                                .multiplier(config.retry.multiplier)
                                .maxDelay(Duration.ofMillis(config.retry.maxDelayMs))
                                .build()
                );

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
