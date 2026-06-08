package com.nexora.runtime.webhook;

import com.nexora.core.execution.ExecutionStatus;
import com.nexora.core.intent.Intent;
import com.nexora.persistence.ExecutionStore;
import com.nexora.persistence.WebhookDeliveryRecord;
import com.nexora.persistence.jdbc.JdbcExecutionStore;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebhookDeliveryServiceTest {

    private HttpServer server;
    private int port;
    private AtomicReference<String> receivedBody;
    private AtomicReference<String> receivedSignature;
    private CountDownLatch latch;
    private ExecutionStore store;

    @BeforeEach
    void setUp() throws Exception {
        receivedBody = new AtomicReference<>();
        receivedSignature = new AtomicReference<>();
        latch = new CountDownLatch(1);
        store = JdbcExecutionStore.h2InMemory();

        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/webhook", exchange -> {
            receivedSignature.set(exchange.getRequestHeaders().getFirst("nexora-signature"));
            try (InputStream is = exchange.getRequestBody()) {
                receivedBody.set(new String(is.readAllBytes(), StandardCharsets.UTF_8));
            }
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
            latch.countDown();
        });
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        port = server.getAddress().getPort();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
        if (store != null) {
            store.close();
        }
    }

    @Test
    void testDeliverIfApplicable_success() throws Exception {
        String secret = "test-secret-123";
        WebhookDeliveryService service = new WebhookDeliveryService(secret, store);
        
        // Seed database with execution to satisfy foreign key constraint
        store.createExecution(new com.nexora.persistence.ExecutionRecord(
                "exec-1", 
                "test-trace", 
                "test_goal", 
                java.util.Map.of(),
                com.nexora.persistence.ExecutionState.RUNNING, 
                java.time.Instant.now(), 
                null,
                java.util.List.of()
        ));

        String webhookUrl = "http://localhost:" + port + "/webhook";
        Intent intent = new Intent("test_goal", Map.of(), null, webhookUrl, List.of(ExecutionStatus.COMPLETED));

        // Note: passing execution id "exec-1"
        service.deliverIfApplicable("exec-1", intent, ExecutionStatus.COMPLETED, Duration.ofMillis(100));

        // Wait for the server to receive the request
        assertTrue(latch.await(3, TimeUnit.SECONDS), "Webhook was not received in time");

        assertNotNull(receivedBody.get());
        assertNotNull(receivedSignature.get());

        // Validate HMAC manually
        Mac mac = Mac.getInstance("HmacSHA256");
        SecretKeySpec secretKeySpec = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        mac.init(secretKeySpec);
        byte[] hmacBytes = mac.doFinal(receivedBody.get().getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder(hmacBytes.length * 2);
        for (byte b : hmacBytes) {
            sb.append(String.format("%02x", b));
        }
        String expectedSignature = sb.toString();

        assertEquals(expectedSignature, receivedSignature.get(), "HMAC signature should match payload");

        // Poll until the async whenComplete block stores the delivery (or timeout)
        List<WebhookDeliveryRecord> deliveries = List.of();
        long deadline = System.currentTimeMillis() + 3_000;
        while (System.currentTimeMillis() < deadline) {
            deliveries = store.getWebhookDeliveries("exec-1");
            if (!deliveries.isEmpty()) break;
            Thread.sleep(50);
        }
        assertEquals(1, deliveries.size());
        assertTrue(deliveries.get(0).successful());
        assertEquals("exec-1", deliveries.get(0).executionId());
        assertEquals(200, deliveries.get(0).statusCode());
        assertEquals(webhookUrl, deliveries.get(0).url());
    }
}
