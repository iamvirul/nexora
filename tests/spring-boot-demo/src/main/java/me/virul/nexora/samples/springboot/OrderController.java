package me.virul.nexora.samples.springboot;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import me.virul.nexora.api.NexoraEngine;
import me.virul.nexora.core.capability.CapabilityResult;
import me.virul.nexora.core.capability.ResultStatus;
import me.virul.nexora.core.execution.ExecutionHandle;
import me.virul.nexora.core.execution.ExecutionResult;
import me.virul.nexora.core.execution.ExecutionStatus;
import me.virul.nexora.core.execution.StepResult;
import me.virul.nexora.core.intent.Intent;
import me.virul.nexora.persistence.ExecutionRecord;
import me.virul.nexora.persistence.ExecutionStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /v1/orders} runs the order workflow and answers with its outcome;
 * {@code GET /v1/executions/{id}} reads an execution back from the store.
 */
@RestController
@RequestMapping("/v1")
public class OrderController {

    private static final Logger log = LoggerFactory.getLogger(OrderController.class);

    private static final Pattern ORDER_ID = Pattern.compile("[A-Za-z0-9-]{1,64}");
    private static final int MAX_AMOUNT_SCALE = 2;
    private static final long EXECUTION_TIMEOUT_SECONDS = 30;

    private final NexoraEngine engine;

    public OrderController(NexoraEngine engine) {
        this.engine = Objects.requireNonNull(engine, "engine must not be null");
    }

    public record OrderRequest(String orderId, BigDecimal amount) {}

    /** {@code paymentId} is set when the order was charged, {@code failureCode} when it was not. */
    public record OrderResponse(String executionId, ExecutionStatus status, String paymentId, String failureCode) {}

    public record ExecutionView(
            String executionId, String goal, String state, Instant startedAt, Instant completedAt) {}

    @PostMapping("/orders")
    public CompletableFuture<ResponseEntity<OrderResponse>> placeOrder(@RequestBody OrderRequest order) {
        validate(order);
        Map<String, Object> context = Map.of(
                OrderPlugin.INPUT_ORDER_ID, order.orderId(),
                OrderPlugin.INPUT_AMOUNT, order.amount().toPlainString());
        ExecutionHandle handle = engine.submit(new Intent(OrderWorkflowConfig.ORDER_GOAL, context));
        log.info("Order submitted orderId={} executionId={}", order.orderId(), handle.executionId());
        // Spring MVC completes the response when the execution does; no request thread waits on it.
        return handle.result()
                .orTimeout(EXECUTION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .thenApply(OrderController::toResponse);
    }

    @GetMapping("/executions/{executionId}")
    public ExecutionView execution(@PathVariable("executionId") String executionId) {
        ExecutionStore store = engine.getStore();
        if (store == null) {
            throw new ApiException(
                    HttpStatus.NOT_FOUND, "PERSISTENCE_DISABLED", "Executions are not stored; set nexora.persistence");
        }
        ExecutionRecord record = store.findById(executionId)
                .orElseThrow(() -> new ApiException(
                        HttpStatus.NOT_FOUND, "EXECUTION_NOT_FOUND", "No execution with id " + executionId));
        return new ExecutionView(
                record.executionId(), record.goal(), record.state().name(), record.startedAt(), record.completedAt());
    }

    private static void validate(OrderRequest order) {
        if (order == null
                || order.orderId() == null
                || !ORDER_ID.matcher(order.orderId()).matches()) {
            throw invalid("orderId must be 1 to 64 letters, digits or dashes");
        }
        if (order.amount() == null
                || order.amount().signum() <= 0
                || order.amount().stripTrailingZeros().scale() > MAX_AMOUNT_SCALE) {
            throw invalid("amount must be positive with at most " + MAX_AMOUNT_SCALE + " decimal places");
        }
    }

    private static ApiException invalid(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", message);
    }

    private static ResponseEntity<OrderResponse> toResponse(ExecutionResult result) {
        String paymentId = null;
        String failureCode = null;
        for (StepResult step : result.stepResults()) {
            CapabilityResult outcome = step.capabilityResult();
            if (outcome.status() == ResultStatus.FAILURE && failureCode == null) {
                failureCode = outcome.failureCode();
            }
            if (OrderWorkflowConfig.STEP_CHARGE.equals(step.stepId())
                    && outcome.succeeded()
                    && outcome.output() instanceof Map<?, ?> output
                    && output.get(OrderPlugin.OUTPUT_PAYMENT_ID) instanceof String id) {
                paymentId = id;
            }
        }
        OrderResponse body = new OrderResponse(result.executionId(), result.status(), paymentId, failureCode);
        HttpStatus status =
                result.status() == ExecutionStatus.COMPLETED ? HttpStatus.OK : HttpStatus.UNPROCESSABLE_CONTENT;
        return ResponseEntity.status(status).body(body);
    }
}
