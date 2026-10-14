package com.nexora.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexora.api.NexoraEngine;
import com.nexora.persistence.StepOutput;
import com.nexora.persistence.jdbc.PoolConfig;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CliConfigPersistenceTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void poolConfigRoundTripsThroughNexoraJson() throws Exception {
        String json = """
            {
                "executionStore": "./nexora-data",
                "persistence": {
                    "pool": {
                        "maximumPoolSize": 8,
                        "minimumIdle": 3,
                        "connectionTimeoutMs": 4000,
                        "idleTimeoutMs": 120000
                    }
                }
            }
            """;
        PoolConfig expected = new PoolConfig(8, 3, 4_000, 120_000);

        CliConfig parsed = JSON.readValue(json, CliConfig.class);
        CliConfig reparsed = JSON.readValue(JSON.writeValueAsString(parsed), CliConfig.class);

        assertEquals(expected, parsed.poolConfig());
        assertEquals(expected, reparsed.poolConfig());
    }

    @Test
    void missingPersistenceSectionUsesDefaults() throws Exception {
        CliConfig config = JSON.readValue("{\"executionStore\": \"./nexora-data\"}", CliConfig.class);

        assertEquals(PoolConfig.defaults(), config.poolConfig());
    }

    @Test
    void explicitNullSectionsUseDefaults() throws Exception {
        CliConfig nullPersistence = JSON.readValue("{\"persistence\": null}", CliConfig.class);
        CliConfig nullPool = JSON.readValue("{\"persistence\": {\"pool\": null}}", CliConfig.class);

        assertEquals(PoolConfig.defaults(), nullPersistence.poolConfig());
        assertEquals(PoolConfig.defaults(), nullPool.poolConfig());
    }

    @Test
    void defaultMinimumIdleShrinksToFitASmallPool() throws Exception {
        CliConfig config = JSON.readValue("{\"persistence\": {\"pool\": {\"maximumPoolSize\": 1}}}", CliConfig.class);

        assertEquals(1, config.poolConfig().minimumIdle());
    }

    @Test
    void outOfRangeValueIsRejected() throws Exception {
        CliConfig config = JSON.readValue("{\"persistence\": {\"pool\": {\"maximumPoolSize\": 0}}}", CliConfig.class);

        assertThrows(IllegalArgumentException.class, config::poolConfig);
    }

    @Test
    void engineFactoryOpensAPooledStoreFromConfig(@TempDir Path dataDir) throws Exception {
        CliConfig config = JSON.readValue("{\"persistence\": {\"pool\": {\"maximumPoolSize\": 2}}}", CliConfig.class);
        config.executionStore = dataDir.resolve("nexora-data").toString();

        try (NexoraEngine engine = EngineFactory.fromConfig(config)) {
            assertTrue(engine.getStore().isHealthy());
        }
    }

    @Test
    void stepOutputCapIsReadFromPersistenceSection() throws Exception {
        CliConfig config = JSON.readValue("{\"persistence\": {\"stepOutputMaxBytes\": 4096}}", CliConfig.class);

        try (NexoraEngine engine = EngineFactory.fromConfig(config)) {
            assertEquals(4096, engine.stepOutputMaxBytes());
        }
    }

    @Test
    void missingStepOutputCapUsesTheDefault() throws Exception {
        CliConfig config = JSON.readValue("{\"persistence\": null}", CliConfig.class);

        try (NexoraEngine engine = EngineFactory.fromConfig(config)) {
            assertEquals(StepOutput.DEFAULT_MAX_BYTES, engine.stepOutputMaxBytes());
        }
    }

    @Test
    void stepOutputCapBelowTheMinimumIsRejected() throws Exception {
        CliConfig config = JSON.readValue("{\"persistence\": {\"stepOutputMaxBytes\": 10}}", CliConfig.class);

        IllegalArgumentException error =
                assertThrows(IllegalArgumentException.class, () -> EngineFactory.fromConfig(config));
        assertTrue(error.getMessage().contains("stepOutputMaxBytes"), error.getMessage());
    }
}
