package com.nexora.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nexora.api.NexoraEngine;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class StoreSelectionTest {

    private final CliConfig config = new CliConfig();
    private final Map<String, String> env = new HashMap<>();

    @Test
    void nothingConfiguredMeansNoStore() {
        assertThat(resolve()).isInstanceOf(StoreSelection.None.class);
    }

    @Test
    void executionStoreSelectsTheH2File() {
        config.executionStore = "./nexora-data";

        assertThat(resolve()).isEqualTo(new StoreSelection.H2File("./nexora-data"));
    }

    @Test
    void jdbcUrlFromTheEnvironmentSelectsThatDatabase() {
        env.put(StoreSelection.JDBC_URL_ENV, "  jdbc:postgresql://db:5432/nexora?user=nexora&password=s3cret ");

        assertThat(resolve())
                .isEqualTo(new StoreSelection.JdbcUrl("jdbc:postgresql://db:5432/nexora?user=nexora&password=s3cret"));
    }

    @Test
    void bothSetIsRefused() {
        config.executionStore = "./nexora-data";
        env.put(StoreSelection.JDBC_URL_ENV, "jdbc:postgresql://db/nexora");

        assertThatThrownBy(this::resolve).hasMessageContaining("keep one");
    }

    @Test
    void unsupportedDatabaseIsRefused() {
        env.put(StoreSelection.JDBC_URL_ENV, "jdbc:mysql://db/nexora");

        assertThatThrownBy(this::resolve).hasMessageContaining("jdbc:mysql, which is not supported");
    }

    @Test
    void nonJdbcValueIsRefusedWithoutEchoingIt() {
        env.put(StoreSelection.JDBC_URL_ENV, "postgres://nexora:s3cret@db/nexora");

        assertThatThrownBy(this::resolve)
                .hasMessageContaining("must be a JDBC URL")
                .message()
                .doesNotContain("s3cret");
    }

    @Test
    void toStringNeverShowsTheUrl() {
        StoreSelection selection = new StoreSelection.JdbcUrl("jdbc:postgresql://db/nexora?password=s3cret");

        assertThat(selection.toString()).isEqualTo("JdbcUrl[postgresql]").doesNotContain("s3cret");
    }

    @Test
    void engineOpensTheStoreNamedByTheJdbcUrl() {
        env.put(StoreSelection.JDBC_URL_ENV, "jdbc:h2:mem:store-selection-test");

        try (NexoraEngine engine = EngineFactory.fromConfig(config, env::get)) {
            assertThat(engine.getStore()).isNotNull();
            assertThat(engine.getStore().isHealthy()).isTrue();
        }
    }

    @Test
    void engineRefusesBothStoresAtStartup() {
        config.executionStore = "./nexora-data";
        env.put(StoreSelection.JDBC_URL_ENV, "jdbc:h2:mem:store-selection-both");

        assertThatThrownBy(() -> EngineFactory.fromConfig(config, env::get))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private StoreSelection resolve() {
        return StoreSelection.resolve(config, env::get);
    }
}
