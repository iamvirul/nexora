package com.nexora.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexora.api.NexoraEngine;
import com.nexora.api.ratelimit.RateLimitConfig;
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

    @Test
    void rateLimitDefaultsApplyWhenTheSectionIsAbsentOrNull() throws Exception {
        RateLimitConfig absent = JSON.readValue("{}", CliConfig.class).rateLimitConfig();
        RateLimitConfig nullObserve =
                JSON.readValue("{\"observe\": null}", CliConfig.class).rateLimitConfig();
        RateLimitConfig nullLimit = JSON.readValue("{\"observe\": {\"rateLimit\": null}}", CliConfig.class)
                .rateLimitConfig();

        assertEquals(RateLimitConfig.defaults(), absent);
        assertEquals(RateLimitConfig.defaults(), nullObserve);
        assertEquals(RateLimitConfig.defaults(), nullLimit);
    }

    @Test
    void rateLimitKeysAreReadFromObserveRateLimit() throws Exception {
        CliConfig config = JSON.readValue(
                "{\"observe\": {\"rateLimit\": {\"globalRpm\": 120, \"executeRpm\": 5,"
                        + " \"trustedProxies\": [\"10.0.0.5\"]}}}",
                CliConfig.class);

        assertEquals(new RateLimitConfig(true, 120, 5, java.util.Set.of("10.0.0.5")), config.rateLimitConfig());
    }

    @Test
    void rateLimitCanBeTurnedOff() throws Exception {
        CliConfig config = JSON.readValue("{\"observe\": {\"rateLimit\": {\"enabled\": false}}}", CliConfig.class);

        assertFalse(config.rateLimitConfig().enabled());
    }

    @Test
    void invalidRateLimitValuesAreRejected() throws Exception {
        CliConfig zero = JSON.readValue("{\"observe\": {\"rateLimit\": {\"executeRpm\": 0}}}", CliConfig.class);
        CliConfig hostName = JSON.readValue(
                "{\"observe\": {\"rateLimit\": {\"trustedProxies\": [\"proxy.internal\"]}}}", CliConfig.class);

        assertThrows(IllegalArgumentException.class, zero::rateLimitConfig);
        assertThrows(IllegalArgumentException.class, hostName::rateLimitConfig);
    }
}
