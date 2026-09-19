package com.nexora.tracing.otel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexora.tracing.Span;
import com.nexora.tracing.SpanStatus;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies {@link OtelTracer} actually exports spans over OTLP/HTTP to a real
 * collector, using a Jaeger all-in-one container (OTLP ingest on 4318, query
 * API on 16686). Named with the {@code *Test} suffix so it runs under the
 * existing surefire configuration in {@code mvn verify} — no build changes
 * needed. Requires a local Docker daemon; GitHub-hosted {@code ubuntu-latest}
 * runners have one preinstalled.
 */
@Testcontainers
class OtelTracerJaegerIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Container
    static final GenericContainer<?> jaeger = new GenericContainer<>(DockerImageName.parse("jaegertracing/all-in-one:latest"))
            .withExposedPorts(4318, 16686)
            .withEnv("COLLECTOR_OTLP_ENABLED", "true")
            .waitingFor(Wait.forHttp("/").forPort(16686).withStartupTimeout(Duration.ofSeconds(60)));

    @Test
    void spansExportedToJaegerCarryRequiredAttributes() throws Exception {
        String otlpEndpoint = "http://" + jaeger.getHost() + ":" + jaeger.getMappedPort(4318);
        OtelTracer tracer = OtelTracer.forEndpoint(otlpEndpoint);

        Span span = tracer.startSpan("capability.send-email", null);
        span.setAttribute("execution_id", "exec-integration-1");
        span.setAttribute("step_id", "step-1");
        span.setAttribute("capability_id", "send-email");
        span.setAttribute("attempt_number", "0");
        span.setStatus(SpanStatus.OK);
        span.end();
        tracer.close(); // flushes the batch processor before shutting down

        JsonNode trace = pollForTrace();

        assertThat(trace).isNotNull();
        JsonNode tags = findSpanTags(trace, "capability.send-email");
        assertThat(tagValue(tags, "execution_id")).isEqualTo("exec-integration-1");
        assertThat(tagValue(tags, "step_id")).isEqualTo("step-1");
        assertThat(tagValue(tags, "capability_id")).isEqualTo("send-email");
        assertThat(tagValue(tags, "attempt_number")).isEqualTo("0");
    }

    private JsonNode pollForTrace() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        String queryUrl = "http://" + jaeger.getHost() + ":" + jaeger.getMappedPort(16686)
                + "/api/traces?service=nexora&limit=1";

        Instant deadline = Instant.now().plusSeconds(15);
        while (Instant.now().isBefore(deadline)) {
            HttpResponse<String> response = client.send(
                    HttpRequest.newBuilder(URI.create(queryUrl)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            JsonNode body = JSON.readTree(response.body());
            JsonNode data = body.get("data");
            if (data != null && data.size() > 0) {
                return data.get(0);
            }
            Thread.sleep(500);
        }
        throw new AssertionError("Timed out waiting for span to appear in Jaeger");
    }

    private static JsonNode findSpanTags(JsonNode trace, String spanName) {
        for (JsonNode spanNode : trace.get("spans")) {
            if (spanName.equals(spanNode.get("operationName").asText())) {
                return spanNode.get("tags");
            }
        }
        throw new AssertionError("Span '" + spanName + "' not found in trace: " + trace);
    }

    private static String tagValue(JsonNode tags, String key) {
        for (JsonNode tag : tags) {
            if (key.equals(tag.get("key").asText())) {
                return tag.get("value").asText();
            }
        }
        throw new AssertionError("Tag '" + key + "' not found in: " + tags);
    }
}
