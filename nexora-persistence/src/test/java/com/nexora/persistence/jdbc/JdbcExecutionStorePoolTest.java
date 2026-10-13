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
import java.util.concurrent.CyclicBarrier;
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
    void concurrentWritesToANewStepAreNeverLost() throws Exception {
        int writers = 8;
        int rounds = 200;
        try (HikariDataSource wide = HikariCPDataSourceFactory.create(
                "jdbc:h2:mem:race_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1",
                new PoolConfig(writers, writers, 5_000, 0))) {
            JdbcExecutionStore store = new JdbcExecutionStore(wide);
            ExecutorService workers = Executors.newFixedThreadPool(writers);
            try {
                List<String> lostKeys = new ArrayList<>();
                for (int round = 0; round < rounds; round++) {
                    String executionId = "race-" + round;
                    store.createExecution(new ExecutionRecord(
                            executionId, "trace", "goal", Map.of(), ExecutionState.RUNNING, T0, null, List.of()));
                    raceOnNewStep(store, workers, executionId, writers);
                    // Serially the keyed write's key always survives (COALESCE keeps it); null means it was dropped.
                    if (store.findById(executionId).orElseThrow().steps().get(0).idempotencyKey() == null) {
                        lostKeys.add(executionId);
                    }
                }
                assertEquals(List.of(), lostKeys);
            } finally {
                workers.shutdownNow();
            }
        }
    }

    /** One writer records the step start with a key, the rest write keyless updates, all released at once. */
    private static void raceOnNewStep(
            JdbcExecutionStore store, ExecutorService workers, String executionId, int writers) throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(writers);
        List<Future<?>> writes = new ArrayList<>();
        for (int i = 0; i < writers; i++) {
            String key = i == 0 ? executionId + "-key" : null;
            writes.add(workers.submit(() -> {
                barrier.await();
                store.upsertStep(executionId, StepRecord.started("step", "cap", key, T0));
                return null;
            }));
        }
        for (Future<?> write : writes) {
            write.get(DEADLOCK_TIMEOUT_SECONDS, TimeUnit.SECONDS);
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
    void constructorFailsFastWithoutLeakingTheUrl() {
        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> new JdbcExecutionStore("jdbc:nexora-no-such-driver:db;password=hunter2"));

        assertTrue(error.getMessage().contains("jdbc:nexora-no-such-driver"));
        assertFalse(error.getMessage().contains("hunter2"));
    }

    @Test
    void inMemoryH2SurvivesThePoolRetiringItsLastConnection() throws Exception {
        // minimumIdle 0 so the pool does not refill: a refill racing the close would keep the database alive.
        try (HikariDataSource idlePool = HikariCPDataSourceFactory.create(
                "jdbc:h2:mem:retire_" + System.nanoTime(), new PoolConfig(POOL_SIZE, 0, 5_000, 0))) {
            JdbcExecutionStore store = new JdbcExecutionStore(idlePool);
            runExecutionLifecycle(store, "before-retire");

            // What maxLifetime and idleTimeout do in production: every open connection, all idle, is closed.
            idlePool.getHikariPoolMXBean().softEvictConnections();
            awaitNoOpenConnections(idlePool);

            assertTrue(store.findById("before-retire").isPresent());
        }
    }

    @Test
    void unnamedInMemoryH2IsRejectedBecauseEachConnectionGetsItsOwnDatabase() {
        assertThrows(
                IllegalArgumentException.class,
                () -> HikariCPDataSourceFactory.create("jdbc:h2:mem:", PoolConfig.singleConnection()));
        assertThrows(
                IllegalArgumentException.class,
                () -> HikariCPDataSourceFactory.create("jdbc:h2:mem:;MODE=PostgreSQL", PoolConfig.singleConnection()));
    }

    /** Eviction closes connections asynchronously; wait until none is open so H2 really sees the last one go. */
    private static void awaitNoOpenConnections(HikariDataSource pool) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (pool.getHikariPoolMXBean().getTotalConnections() > 0) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("Pool still holds connections after eviction");
            }
            Thread.sleep(10);
        }
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
