package com.nexora.executor;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

/**
 * Stop signal shared between whoever wants an execution to stop (the deadline watchdog,
 * a cancel request) and the {@link DagStepScheduler} running it.
 *
 * <p>The first {@link #halt(Reason)} wins; later calls return {@code false}. Once the scheduler
 * has collected the final result it {@linkplain #seal() seals} the signal, after which halts are
 * rejected too. That makes "did my cancel take effect?" a reliable answer: a cancel racing a plan
 * that just finished either lands before the seal (result is CANCELLED) or after it (returns false).
 *
 * <p>Steps register the thread they run on via {@link #enterStep()} so that a
 * {@link Reason#CANCELLED} halt can interrupt them. Interrupts are cleared again in
 * {@link #exitStep()} so they never leak into the next task on a pooled thread.
 *
 * <p>All state is guarded by {@code this}; the critical sections are tiny and never block.
 */
public final class HaltSignal {

    public enum Reason {
        /** Plan deadline expired. Pending steps are suppressed; running steps finish normally. */
        DEADLINE(false),
        /** Execution was cancelled. Pending steps are suppressed; running steps are interrupted. */
        CANCELLED(true);

        private final boolean interruptsRunningSteps;

        Reason(boolean interruptsRunningSteps) {
            this.interruptsRunningSteps = interruptsRunningSteps;
        }
    }

    private final Set<Thread> runningSteps = new HashSet<>();
    private final Set<Thread> interruptedSteps = new HashSet<>();
    private Reason reason;
    private boolean sealed;

    /**
     * Requests the execution to stop.
     *
     * @return {@code true} if this call halted the execution; {@code false} if it was already
     *         halted or has already finished
     */
    public synchronized boolean halt(Reason reason) {
        if (sealed || this.reason != null) {
            return false;
        }
        this.reason = reason;
        if (reason.interruptsRunningSteps) {
            for (Thread thread : runningSteps) {
                interruptedSteps.add(thread);
                thread.interrupt();
            }
        }
        return true;
    }

    public synchronized Optional<Reason> reason() {
        return Optional.ofNullable(reason);
    }

    /**
     * Registers the calling thread as running a step. Returns {@code false} without registering
     * if the execution is already halted, in which case the step must not start.
     */
    synchronized boolean enterStep() {
        if (reason != null) {
            return false;
        }
        runningSteps.add(Thread.currentThread());
        return true;
    }

    /** Called on the step's thread, in a {@code finally} after a successful {@link #enterStep()}. */
    synchronized void exitStep() {
        Thread current = Thread.currentThread();
        runningSteps.remove(current);
        if (interruptedSteps.remove(current)) {
            // Clear the interrupt we delivered so it does not hit the next task on this thread.
            Thread.interrupted();
        }
    }

    /**
     * Rejects all future halts and returns the reason the execution was halted, if any.
     * Called once by the scheduler when the last step has finished.
     */
    synchronized Optional<Reason> seal() {
        sealed = true;
        return Optional.ofNullable(reason);
    }
}
