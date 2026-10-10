package com.nexora.executor.interceptor;

import com.nexora.core.capability.CapabilityRequest;
import com.nexora.core.capability.CapabilityResult;
import com.nexora.executor.ExecutionInterceptor;
import com.nexora.executor.InterceptorChain;

import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Enforces a per-capability timeout by running the rest of the chain on {@code executor} and
 * waiting at most the timeout for it.
 *
 * <p>Uses a {@link FutureTask} rather than a {@code CompletableFuture} because only
 * {@code FutureTask.cancel(true)} actually interrupts the worker thread. On timeout, or when the
 * waiting step thread is itself interrupted (execution cancelled), the capability is interrupted
 * instead of being left running in the background.
 */
public final class TimeoutInterceptor implements ExecutionInterceptor {

    private final Executor executor;
    private final Duration defaultTimeout;

    public TimeoutInterceptor(Executor executor, Duration defaultTimeout) {
        this.executor = executor;
        this.defaultTimeout = defaultTimeout;
    }

    @Override
    public CapabilityResult intercept(CapabilityRequest request, InterceptorChain chain) {
        Duration timeout = request.timeout() != null ? request.timeout() : defaultTimeout;
        if (timeout == null) {
            return chain.proceed(request);
        }

        FutureTask<CapabilityResult> task = new FutureTask<>(() -> chain.proceed(request));
        executor.execute(task);

        try {
            return task.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            task.cancel(true);
            return CapabilityResult.failure(
                    "EXECUTION_TIMEOUT",
                    "Capability " + request.capabilityId() + " exceeded timeout of " + timeout
            );
        } catch (InterruptedException e) {
            task.cancel(true);
            Thread.currentThread().interrupt();
            return CapabilityResult.failure("INTERRUPTED", "Execution was interrupted");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) throw re;
            throw new RuntimeException("Capability execution failed", cause);
        }
    }
}
