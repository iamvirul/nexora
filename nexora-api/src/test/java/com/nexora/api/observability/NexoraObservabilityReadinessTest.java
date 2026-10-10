package com.nexora.api.observability;

import com.nexora.api.NexoraEngine;
import com.nexora.persistence.jdbc.JdbcExecutionStore;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NexoraObservabilityReadinessTest {

    @Test
    void readyGaugeIsOneWhenEngineIsReady() {
        try (NexoraEngine engine = NexoraEngine.builder()
                .withExecutionStore(JdbcExecutionStore.h2InMemory())
                .build();
             NexoraObservability observability = NexoraObservability.attach(engine)) {

            assertThat(observability.scrapePrometheus()).containsPattern("(?m)^nexora_ready 1\\.0$");
        }
    }

    @Test
    void readyGaugeDropsToZeroWhenPersistenceIsUnreachable() {
        JdbcExecutionStore store = JdbcExecutionStore.h2InMemory();
        try (NexoraEngine engine = NexoraEngine.builder().withExecutionStore(store).build();
             NexoraObservability observability = NexoraObservability.attach(engine)) {
            store.close();

            assertThat(observability.scrapePrometheus()).containsPattern("(?m)^nexora_ready 0\\.0$");
        }
    }
}
