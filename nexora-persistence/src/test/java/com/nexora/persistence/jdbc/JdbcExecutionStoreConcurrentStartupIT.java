package com.nexora.persistence.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Several processes opening the store against the same, still empty, PostgreSQL database at once:
 * what happens when a Deployment with more than one replica starts for the first time. Every one of
 * them must come up, without crashing on the others' half-finished schema setup.
 *
 * <p>Tagged {@code integration}; needs Docker, like {@link JdbcExecutionStorePostgresIT}.
 */
@Tag("integration")
@Testcontainers
class JdbcExecutionStoreConcurrentStartupIT {

    private static final DockerImageName POSTGRES_IMAGE = DockerImageName.parse("postgres:15-alpine");
    private static final int REPLICAS = 4;
    private static final int ROUNDS = 8;
    private static final long STARTUP_TIMEOUT_SECONDS = 60;

    @Container
    private static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(POSTGRES_IMAGE);

    @Test
    void storesStartingTogetherOnAnEmptyDatabaseAllComeUp() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(REPLICAS);
        try {
            for (int round = 0; round < ROUNDS; round++) {
                String database = "startup_" + round;
                createDatabase(database);
                String url = urlFor(database);
                CountDownLatch start = new CountDownLatch(1);
                List<Future<JdbcExecutionStore>> replicas = new ArrayList<>();
                for (int i = 0; i < REPLICAS; i++) {
                    replicas.add(pool.submit(() -> {
                        start.await();
                        return new JdbcExecutionStore(url, PoolConfig.defaults());
                    }));
                }

                start.countDown();

                for (Future<JdbcExecutionStore> replica : replicas) {
                    // get() rethrows any startup failure, failing the test with its cause.
                    try (JdbcExecutionStore store = replica.get(STARTUP_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                        assertThat(store.isHealthy()).isTrue();
                        assertThat(store.isSchemaCurrent()).isTrue();
                    }
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private static void createDatabase(String name) throws SQLException {
        try (Connection conn = DriverManager.getConnection(
                        postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                Statement statement = conn.createStatement()) {
            statement.execute("CREATE DATABASE " + name);
        }
    }

    private static String urlFor(String database) {
        String base = postgres.getJdbcUrl().replaceFirst("/[^/?]+(\\?|$)", "/" + database + "$1");
        String separator = base.contains("?") ? "&" : "?";
        return base + separator + "user=" + postgres.getUsername() + "&password=" + postgres.getPassword();
    }
}
