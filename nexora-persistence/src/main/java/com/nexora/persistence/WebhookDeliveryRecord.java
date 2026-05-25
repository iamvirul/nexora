package com.nexora.persistence;

import java.time.Instant;

public record WebhookDeliveryRecord(
        String deliveryId,
        String executionId,
        String url,
        int statusCode,
        int attempt,
        boolean successful,
        Instant timestamp,
        String errorMessage
) {}
