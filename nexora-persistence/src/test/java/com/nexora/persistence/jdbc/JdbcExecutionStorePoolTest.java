package com.nexora.persistence.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nexora.persistence.ExecutionFilter;
import com.nexora.persistence.ExecutionRecord;
import com.nexora.persistence.ExecutionState;
import com.nexora.persistence.StepRecord;
import com.nexora.persistence.StepState;
import com.zaxxer.hikari.HikariDataSource;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class JdbcExecutionStorePoolTest {

    private static final Instant T0 = Instant.parse("2026-10-01T10:00:00Z");
    private static final int CONCURRENT_EXECUTIONS = 10;
    private static final int POOL_SIZE = 2;
    private static final long DEADLOCK_TIMEOUT_SECONDS = 30;

    private HikariDataSource pool;

    @BeforeEach
    void setUp() {
        pool = HikariCPDataSourceFactory.create(
                "jdbc:h2:mem:pool_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1",
                new PoolConfig(POOL_SIZE, POOL_SIZE, 5_000, 0));
    }

    @AfterEach
    void tearDown() {
        pool.close();
    }

    @Test
    void tenConcurrentExecutionsCompleteWithoutDeadlockOnAPoolOfTwo() throws Exception {
        JdbcExecutionStore store = new JdbcExecutionStore(pool);
        CountDownLatch startGate = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(CONCURRENT_EXECUTIONS);
        try {
            List<Future<ExecutionRecord>> results = new ArrayList<>();
            for (int i = 0; i < CONCURRENT_EXECUTIONS; i++) {
                String executionId = "exec-" + i;
                results.add(workers.submit(() -> {
                    startGate.await();
                    return runExecutionLifecycle(store, executionId);
                }));
            }
            startGate.countDown();

            for (Future<ExecutionRecord> result : results) {
                ExecutionRecord stored = result.get(DEADLOCK_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                assertEquals(ExecutionState.COMPLETED, stored.state());
                assertEquals(2, stored.steps().size());
                assertTrue(stored.steps().stream().allMatch(s -> s.state() == StepState.COMPLETED));
                assertTrue(stored.steps().stream().allMatch(s -> s.idempotencyKey() != null));
            }
            assertEquals(CONCURRENT_EXECUTIONS, store.count(ExecutionFilter.all()));
        } finally {
            workers.shutdownNow();
        }
    }

    @Test
    void closeLeavesACallerOwnedDataSourceOpen() {
        JdbcExecutionStore store = new JdbcExecutionStore(pool);

        store.close();

        assertFalse(pool.isClosed());
        assertTrue(new JdbcExecutionStore(pool).isHealthy());
    }

    @Test
    void pooledConstructorOwnsAndClosesItsPool() {
        JdbcExecutionStore store = new JdbcExecutionStore(
                "jdbc:h2:mem:owned_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1", PoolConfig.defaults());
        assertTrue(store.isHealthy());

        store.close();

        assertFalse(store.isHealthy());
    }

    @Test
    void constructorFailsFastWithoutLeakingUrlParameters() {
        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> new JdbcExecutionStore("jdbc:nexora-no-such-driver:db;password=hunter2"));

        assertTrue(error.getMessage().contains("jdbc:nexora-no-such-driver:db"));
        assertFalse(error.getMessage().contains("hunter2"));
    }

    /** Mirrors the engine's write pattern: create, step start, step completion (no key), finish, read back. */
    private static ExecutionRecord runExecutionLifecycle(JdbcExecutionStore store, String executionId) {
        store.createExecution(new ExecutionRecord(
                executionId, "trace", "goal", Map.of(), ExecutionState.RUNNING, T0, null, List.of()));
        for (String stepId : List.of("reserve", "charge")) {
            store.upsertStep(executionId, StepRecord.started(stepId, "cap", executionId + "-" + stepId, T0));
            store.upsertStep(
                    executionId, StepRecord.started(stepId, "cap", null, T0).completed(T0.plusMillis(5)));
        }
        store.updateExecution(executionId, ExecutionState.COMPLETED, T0.plusSeconds(1));
        store.findRecent(CONCURRENT_EXECUTIONS);
        return store.findById(executionId).orElseThrow();
    }
}
