package me.virul.nexora.loader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class InvocationLeasesTest {

    private final AtomicInteger releases = new AtomicInteger();
    private final InvocationLeases leases = new InvocationLeases(releases::incrementAndGet);

    @Test
    void retireReleasesAtOnceWhenNothingIsInFlight() {
        leases.retire();

        assertEquals(1, releases.get());
        assertTrue(leases.released());
    }

    @Test
    void retireWaitsForTheLastLease() {
        assertTrue(leases.tryAcquire());
        assertTrue(leases.tryAcquire());

        leases.retire();
        assertEquals(0, releases.get());
        leases.release();
        assertEquals(0, releases.get());
        leases.release();

        assertEquals(1, releases.get());
    }

    @Test
    void leasesAreRefusedOnceReleased() {
        leases.retire();

        assertFalse(leases.tryAcquire());
    }

    @Test
    void leasesAreStillGrantedAfterRetireWhileInFlight() {
        assertTrue(leases.tryAcquire());
        leases.retire();

        assertTrue(leases.tryAcquire(), "an invocation that found the old version just before retire may still run");
        leases.release();
        leases.release();
        assertEquals(1, releases.get());
    }

    @Test
    void retireIsIdempotent() {
        leases.retire();
        leases.retire();

        assertEquals(1, releases.get());
    }

    @Test
    void unmatchedReleaseThrows() {
        assertThrows(IllegalStateException.class, leases::release);
    }

    @Test
    void releaseActionRunsExactlyOnceAndNeverUnderALeaseWhenRacing() throws Exception {
        int rounds = 200;
        int workers = 8;
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        try {
            for (int round = 0; round < rounds; round++) {
                AtomicBoolean releasedFlag = new AtomicBoolean();
                AtomicInteger runs = new AtomicInteger();
                AtomicBoolean ranAfterRelease = new AtomicBoolean();
                InvocationLeases raced = new InvocationLeases(() -> {
                    runs.incrementAndGet();
                    releasedFlag.set(true);
                });
                CountDownLatch start = new CountDownLatch(1);
                List<Future<?>> tasks = new ArrayList<>();
                for (int w = 0; w < workers; w++) {
                    tasks.add(pool.submit(() -> {
                        start.await();
                        for (int i = 0; i < 50; i++) {
                            if (raced.tryAcquire()) {
                                if (releasedFlag.get()) ranAfterRelease.set(true);
                                raced.release();
                            }
                        }
                        return null;
                    }));
                }
                start.countDown();
                raced.retire();
                for (Future<?> task : tasks) {
                    task.get(5, TimeUnit.SECONDS);
                }
                assertEquals(1, runs.get(), "release action must run exactly once");
                assertFalse(ranAfterRelease.get(), "no lease may be granted after release");
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
