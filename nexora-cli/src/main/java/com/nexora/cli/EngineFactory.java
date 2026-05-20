package com.nexora.cli;

import com.nexora.api.NexoraEngine;
import com.nexora.planner.model.StepDefinition;
import com.nexora.retry.ExponentialBackoffPolicy;

import java.time.Duration;

final class EngineFactory {

    private EngineFactory() {}

    static NexoraEngine fromConfig(CliConfig config) {
        NexoraEngine.Builder builder = NexoraEngine.builder()
                .withDefaultRetryPolicy(
                        ExponentialBackoffPolicy.builder()
                                .maxAttempts(config.retry.maxAttempts)
                                .initialDelay(Duration.ofMillis(config.retry.initialDelayMs))
                                .multiplier(config.retry.multiplier)
                                .maxDelay(Duration.ofMillis(config.retry.maxDelayMs))
                                .build()
                );

        for (CliConfig.StepConfig sc : config.steps) {
            String match = sc.matchesGoalContains;
            builder.withStepDefinition(new StepDefinition(
                    sc.id,
                    sc.capabilityId,
                    goal -> match == null || goal.contains(match)
            ));
        }

        return builder.build();
    }
}
