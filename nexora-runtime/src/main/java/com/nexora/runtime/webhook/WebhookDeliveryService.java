package com.nexora.runtime.webhook;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexora.core.execution.ExecutionStatus;
import com.nexora.core.intent.Intent;
import com.nexora.persistence.ExecutionStore;
import com.nexora.persistence.WebhookDeliveryRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class WebhookDeliveryService {

    private static final Logger log = LoggerFactory.getLogger(WebhookDeliveryService.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final String webhookSecret;
    private final ExecutionStore store;
    private final HttpClient httpClient;
    private final Executor executor;

    public WebhookDeliveryService(String webhookSecret, ExecutionStore store) {
        this(webhookSecret, store, Executors.newVirtualThreadPerTaskExecutor());
    }

    public WebhookDeliveryService(String webhookSecret, ExecutionStore store, Executor executor) {
        this.webhookSecret = webhookSecret;
        this.store = store;
        this.executor = executor;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .executor(executor)
                .build();
    }

    public void deliverIfApplicable(String executionId, Intent intent, ExecutionStatus status, Duration duration) {
        if (intent.getWebhookUrl() == null || intent.getWebhookUrl().isBlank()) {
            return;
        }
        if (!intent.getWebhookEvents().contains(status)) {
            return;
        }

        Map<String, Object> payloadMap = Map.of(
                "executionId", executionId,
                "status", status.name(),
                "goal", intent.getGoal(),
                "durationMs", duration.toMillis()
        );

        try {
            String payload = JSON.writeValueAsString(payloadMap);
            String signature = generateHmacSha256(payload, webhookSecret);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(intent.getWebhookUrl()))
                    .header("Content-Type", "application/json")
                    .header("nexora-signature", signature)
                    .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                    .build();

            // Fire and forget, retry up to 3 times
            CompletableFuture.runAsync(() -> attemptDelivery(request, executionId, intent.getWebhookUrl(), 1), executor);
        } catch (Exception e) {
            log.error("Failed to prepare webhook delivery executionId={}", executionId, e);
        }
    }

    private void attemptDelivery(HttpRequest request, String executionId, String url, int attempt) {
        Instant now = Instant.now();
        String deliveryId = UUID.randomUUID().toString();

        httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .whenComplete((response, error) -> {
                    boolean success = false;
                    int statusCode = 0;
                    String errorMessage = null;

                    if (error != null) {
                        errorMessage = error.getMessage();
                        log.warn("Webhook delivery failed executionId={} url={} attempt={} error={}",
                                executionId, url, attempt, errorMessage);
                    } else {
                        statusCode = response.statusCode();
                        if (statusCode >= 200 && statusCode < 300) {
                            success = true;
                        } else {
                            errorMessage = "HTTP " + statusCode;
                            log.warn("Webhook delivery failed executionId={} url={} attempt={} statusCode={}",
                                    executionId, url, attempt, statusCode);
                        }
                    }

                    if (store != null) {
                        try {
                            store.recordWebhookDelivery(new WebhookDeliveryRecord(
                                    deliveryId, executionId, url, statusCode, attempt, success, now, errorMessage
                            ));
                        } catch (Exception e) {
                            log.error("Failed to store webhook delivery executionId={}", executionId, e);
                        }
                    }

                    if (!success && attempt < 3) {
                        long backoffMs = (long) (200 * Math.pow(2, attempt - 1));
                        CompletableFuture.delayedExecutor(backoffMs, TimeUnit.MILLISECONDS, executor)
                                .execute(() -> attemptDelivery(request, executionId, url, attempt + 1));
                    }
                });
    }

    private String generateHmacSha256(String data, String secret) throws Exception {
        if (secret == null) secret = "";
        Mac mac = Mac.getInstance("HmacSHA256");
        SecretKeySpec secretKeySpec = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        mac.init(secretKeySpec);
        byte[] hmacBytes = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder(hmacBytes.length * 2);
        for (byte b : hmacBytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
