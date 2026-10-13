package com.nexora.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nexora.api.NexoraEngine.HealthStatus;
import com.nexora.api.NexoraEngine.ReadinessReport;
import com.nexora.persistence.jdbc.JdbcExecutionStore;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

class NexoraEngineTest {

    @Test
    void builderRejectsInvalidDefaultPlanDeadline() {
        NexoraEngine.Builder builder = NexoraEngine.builder();

        assertThatThrownBy(() -> builder.withDefaultPlanDeadline(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("defaultPlanDeadline must be a positive duration");

        assertThatThrownBy(() -> builder.withDefaultPlanDeadline(Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("defaultPlanDeadline must be a positive duration");

        assertThatThrownBy(() -> builder.withDefaultPlanDeadline(Duration.ofSeconds(-5)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("defaultPlanDeadline must be a positive duration");
    }

    @Test
    void readinessIsUpWhenAllDependenciesAreHealthy() {
        try (NexoraEngine engine = NexoraEngine.builder()
                .withExecutionStore(JdbcExecutionStore.h2InMemory())
                .build()) {

            ReadinessReport report = engine.readiness();

            assertThat(report.ready()).isTrue();
            assertThat(report.status()).isEqualTo(HealthStatus.UP);
            assertThat(report.checks())
                    .containsExactly(
                            Map.entry(ReadinessReport.CHECK_PERSISTENCE, HealthStatus.UP),
                            Map.entry(ReadinessReport.CHECK_MIGRATIONS, HealthStatus.UP),
                            Map.entry(ReadinessReport.CHECK_PLUGINS, HealthStatus.UP),
                            Map.entry(ReadinessReport.CHECK_EXECUTOR, HealthStatus.UP));
        }
    }

    @Test
    void readinessTreatsDisabledPersistenceAsUp() {
        try (NexoraEngine engine = NexoraEngine.builder().build()) {
            assertThat(engine.readiness().checks())
                    .containsEntry(ReadinessReport.CHECK_PERSISTENCE, HealthStatus.UP)
                    .containsEntry(ReadinessReport.CHECK_MIGRATIONS, HealthStatus.UP);
        }
    }

    @Test
    void readinessIsDownWhenASchemaMigrationIsRecordedAsFailed() throws Exception {
        JdbcDataSource database = new JdbcDataSource();
        database.setURL("jdbc:h2:mem:readiness_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        try (NexoraEngine engine = NexoraEngine.builder()
                .withExecutionStore(new JdbcExecutionStore(database))
                .build()) {
            // As left behind by a newer instance whose migration failed part way.
            try (Connection conn = database.getConnection();
                    Statement stmt = conn.createStatement()) {
                stmt.execute("UPDATE \"nexora_schema_history\" SET \"success\" = FALSE WHERE \"version\" = '1'");
            }

            ReadinessReport report = engine.readiness();

            assertThat(report.ready()).isFalse();
            assertThat(report.checks())
                    .containsEntry(ReadinessReport.CHECK_PERSISTENCE, HealthStatus.UP)
                    .containsEntry(ReadinessReport.CHECK_MIGRATIONS, HealthStatus.DOWN);
        }
    }

    @Test
    void readinessIsDownWhenPersistenceIsUnreachable() {
        JdbcExecutionStore store = JdbcExecutionStore.h2InMemory();
        try (NexoraEngine engine =
                NexoraEngine.builder().withExecutionStore(store).build()) {
            store.close();

            ReadinessReport report = engine.readiness();

            assertThat(report.ready()).isFalse();
            assertThat(report.status()).isEqualTo(HealthStatus.DOWN);
            assertThat(report.checks())
                    .containsEntry(ReadinessReport.CHECK_PERSISTENCE, HealthStatus.DOWN)
                    .containsEntry(ReadinessReport.CHECK_PLUGINS, HealthStatus.UP);
        }
    }

    @Test
    void readinessIsDownWhenSuppliedExecutorIsShutDown() {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (NexoraEngine engine = NexoraEngine.builder().withExecutor(executor).build()) {
            executor.shutdown();

            ReadinessReport report = engine.readiness();

            assertThat(report.ready()).isFalse();
            assertThat(report.checks()).containsEntry(ReadinessReport.CHECK_EXECUTOR, HealthStatus.DOWN);
        }
    }

    @Test
    void readinessReportChecksCannotBeModified() {
        try (NexoraEngine engine = NexoraEngine.builder().build()) {
            Map<String, HealthStatus> checks = engine.readiness().checks();

            assertThatThrownBy(() -> checks.put(ReadinessReport.CHECK_PLUGINS, HealthStatus.DOWN))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }
}
