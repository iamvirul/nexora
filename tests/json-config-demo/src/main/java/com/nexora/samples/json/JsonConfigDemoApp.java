package com.nexora.samples.json;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexora.api.NexoraEngine;
import com.nexora.core.capability.CapabilityContract;
import com.nexora.core.capability.CapabilityResult;
import com.nexora.core.execution.ExecutionStatus;
import com.nexora.core.intent.Intent;
import com.nexora.planner.model.StepDefinition;
import com.nexora.retry.ExponentialBackoffPolicy;
import com.nexora.spi.Capability;
import com.nexora.spi.CapabilityDescriptor;
import com.nexora.spi.CapabilityProvider;
import com.nexora.spi.NexoraPlugin;
import com.nexora.spi.PluginContext;
import com.nexora.spi.PluginDescriptor;
import com.nexora.core.plan.StepCondition;
import com.nexora.core.plan.condition.ContextValueEquals;
import com.sun.net.httpserver.HttpServer;

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

public class JsonConfigDemoApp {

    static final int HTTP_PORT = 9466;
    static final ObjectMapper JSON = new ObjectMapper();

    public static class Config {
        public List<StepConfig> steps = List.of();
        public RetryConfig retry = new RetryConfig();
        public String webhookSecret;
    }

    public static class StepConfig {
        public String id;
        public String capabilityId;
        public String matchesGoalContains;
        public ConditionConfig condition;
    }

    public static class ConditionConfig {
        public String type;
        public String key;
        public Object value;
        
        public StepCondition toStepCondition() {
            if ("contextValueEquals".equals(type)) {
                return new ContextValueEquals(key, value);
            }
            return null; // simplified for demo
        }
    }

    public static class RetryConfig {
        public int maxAttempts = 3;
        public long initialDelayMs = 200;
        public double multiplier = 2.0;
        public long maxDelayMs = 10_000;
    }

    public static void main(String[] args) throws Exception {
        System.out.println("Loading configuration from nexora.json...");

        Config config;
        try (InputStream is = JsonConfigDemoApp.class.getClassLoader().getResourceAsStream("nexora.json")) {
            if (is == null) {
                throw new IllegalStateException("nexora.json resource not found on classpath");
            }
            config = JSON.readValue(is, Config.class);
        }

        if (config.webhookSecret == null || config.webhookSecret.trim().isEmpty()) {
            throw new IllegalArgumentException("webhookSecret is required in nexora.json configuration");
        }

        NexoraEngine.Builder builder = NexoraEngine.builder()
                .withWebhookSecret(config.webhookSecret)
                .withDefaultRetryPolicy(
                        ExponentialBackoffPolicy.builder()
                                .maxAttempts(config.retry.maxAttempts)
                                .initialDelay(Duration.ofMillis(config.retry.initialDelayMs))
                                .multiplier(config.retry.multiplier)
                                .maxDelay(Duration.ofMillis(config.retry.maxDelayMs))
                                .build()
                )
                .withPlugin(buildPlugin());

        for (StepConfig sc : config.steps) {
            String match = sc.matchesGoalContains;
            StepDefinition.Builder sdb = StepDefinition.builder(sc.id, sc.capabilityId)
                    .withMatcher(goal -> match == null || goal.contains(match));
            if (sc.condition != null) {
                sdb.withCondition(sc.condition.toStepCondition());
            }
            builder.withStepDefinition(sdb.build());
        }

        NexoraEngine engine = builder.build();
        
        System.out.println("Configured NexoraEngine with " + config.steps.size() + " steps and webhook secret.");

        // Start mock webhook server
        CountDownLatch shutdown = new CountDownLatch(1);
        HttpServer http = HttpServer.create(new InetSocketAddress(HTTP_PORT), 0);
        http.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        http.createContext("/webhook", exchange -> {
            String signature = exchange.getRequestHeaders().getFirst("nexora-signature");
            byte[] body = exchange.getRequestBody().readAllBytes();
            System.out.println("\n  >>> WEBHOOK CALLBACK RECEIVED!");
            System.out.println("  >>> Signature: " + signature);
            System.out.println("  >>> Payload: " + new String(body, StandardCharsets.UTF_8));

            try {
                Mac mac = Mac.getInstance("HmacSHA256");
                mac.init(new SecretKeySpec(config.webhookSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
                byte[] hmacBytes = mac.doFinal(body);
                StringBuilder sb = new StringBuilder();
                for (byte b : hmacBytes) sb.append(String.format("%02x", b));
                
                System.out.println("  >>> Calculated HMAC: " + sb.toString());
                if (sb.toString().equals(signature)) {
                    System.out.println("  >>> SUCCESS: Signature matches perfectly!");
                } else {
                    System.err.println("  >>> ERROR: Signature mismatch!");
                }
            } catch (Exception e) {
                e.printStackTrace();
            }

            exchange.sendResponseHeaders(200, -1);
            exchange.close();
            shutdown.countDown();
        });
        http.start();

        System.out.println("Starting execution: 'generate a daily report and send email'...");

        try {
            engine.execute(new Intent(
                    "generate a daily report and send email",
                    Map.of("reportType", "daily", "send_email_enabled", true),
                    null,
                    "http://localhost:" + HTTP_PORT + "/webhook",
                    List.of(ExecutionStatus.COMPLETED)
            ));

            shutdown.await(30, java.util.concurrent.TimeUnit.SECONDS);
        } finally {
            http.stop(0);
        }
        System.out.println("Demo complete!");
    }

    static NexoraPlugin buildPlugin() {
        return new NexoraPlugin() {
            @Override public PluginDescriptor descriptor() {
                return new PluginDescriptor("demo-plugin", "1.0", "Demo capabilities", List.of(), null);
            }
            @Override public void initialize(PluginContext ctx) {}
            @Override public void shutdown() {}
            @Override public List<CapabilityProvider> capabilityProviders() {
                return List.of(
                    cap("generate_report", req -> {
                        System.out.println("  [generate_report] Generating report...");
                        try { Thread.sleep(200); } catch (InterruptedException e) {}
                        return CapabilityResult.success(Map.of("reportId", "REP-123"));
                    }),
                    cap("send_email", req -> {
                        System.out.println("  [send_email] Sending report email...");
                        try { Thread.sleep(200); } catch (InterruptedException e) {}
                        return CapabilityResult.success(Map.of("emailSent", true));
                    })
                );
            }
        };
    }

    static CapabilityProvider cap(String id, Capability impl) {
        return new CapabilityProvider() {
            @Override public CapabilityDescriptor descriptor() {
                return new CapabilityDescriptor(id, id, List.of(), List.of(), true, false, CapabilityContract.none());
            }
            @Override public Capability create(PluginContext ctx) { return impl; }
        };
    }
}
