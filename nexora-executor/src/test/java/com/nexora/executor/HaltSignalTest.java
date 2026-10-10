package com.nexora.executor;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HaltSignalTest {

    @AfterEach
    void clearInterruptFlag() {
        // Some tests interrupt the test thread on purpose; never leak that into the next test.
        Thread.interrupted();
    }

    @Test
    void firstHaltWinsAndLaterHaltsAreRejected() {
        HaltSignal halt = new HaltSignal();

        assertTrue(halt.halt(HaltSignal.Reason.DEADLINE));
        assertFalse(halt.halt(HaltSignal.Reason.CANCELLED));
        assertEquals(Optional.of(HaltSignal.Reason.DEADLINE), halt.reason());
    }

    @Test
    void sealRejectsLaterHaltsAndReportsNoReasonWhenNeverHalted() {
        HaltSignal halt = new HaltSignal();

        assertEquals(Optional.empty(), halt.seal());
        assertFalse(halt.halt(HaltSignal.Reason.CANCELLED));
        assertEquals(Optional.empty(), halt.reason());
    }

    @Test
    void sealReturnsReasonOfEarlierHalt() {
        HaltSignal halt = new HaltSignal();
        halt.halt(HaltSignal.Reason.CANCELLED);

        assertEquals(Optional.of(HaltSignal.Reason.CANCELLED), halt.seal());
    }

    @Test
    void enterStepIsRejectedOnceHalted() {
        HaltSignal halt = new HaltSignal();
        halt.halt(HaltSignal.Reason.CANCELLED);

        assertFalse(halt.enterStep());
    }

    @Test
    void cancelInterruptsRunningStepAndExitStepClearsTheInterrupt() {
        HaltSignal halt = new HaltSignal();
        assertTrue(halt.enterStep());

        halt.halt(HaltSignal.Reason.CANCELLED);
        assertTrue(Thread.currentThread().isInterrupted(), "running step should be interrupted");

        halt.exitStep();
        assertFalse(Thread.currentThread().isInterrupted(), "interrupt must not leak past the step");
    }

    @Test
    void deadlineDoesNotInterruptRunningStep() {
        HaltSignal halt = new HaltSignal();
        assertTrue(halt.enterStep());

        halt.halt(HaltSignal.Reason.DEADLINE);

        assertFalse(Thread.currentThread().isInterrupted());
        halt.exitStep();
    }

    @Test
    void exitStepLeavesInterruptsItDidNotDeliver() {
        HaltSignal halt = new HaltSignal();
        assertTrue(halt.enterStep());
        Thread.currentThread().interrupt(); // e.g. executor shutdownNow(), not ours to clear

        halt.exitStep();

        assertTrue(Thread.currentThread().isInterrupted());
    }
}
