package me.virul.nexora.samples.springboot;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import me.virul.nexora.api.NexoraEngine;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/** Runs the real application on a random port and drives it over HTTP, as a client would. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SpringBootDemoAppTest {

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpClient http =
            HttpClient.newBuilder().connectTimeout(REQUEST_TIMEOUT).build();

    @LocalServerPort
    private int port;

    @Autowired
    private NexoraEngine engine;

    @Autowired
    private OrderPlugin orderPlugin;

    @Test
    void starterWiresPluginStepsAndPersistenceIntoTheEngine() {
        assertThat(engine.activePluginIds()).containsExactly(OrderPlugin.PLUGIN_ID);
        assertThat(engine.getStore()).isNotNull();
        assertThat(engine.readiness().ready()).isTrue();
    }

    @Test
    void placesOrderAndChargesIt() throws Exception {
        int paymentsBefore = orderPlugin.paymentCount();

        HttpResponse<String> response = post("/v1/orders", "{\"orderId\":\"ORD-1\",\"amount\":42.50}");

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = JSON.readTree(response.body());
        assertThat(body.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(body.get("paymentId").asText()).startsWith("PAY-");
        assertThat(body.get("failureCode").isNull()).isTrue();
        assertThat(orderPlugin.paymentCount()).isEqualTo(paymentsBefore + 1);
    }

    @Test
    void rejectsOrderAboveLimitWithoutCharging() throws Exception {
        int paymentsBefore = orderPlugin.paymentCount();

        HttpResponse<String> response = post("/v1/orders", "{\"orderId\":\"ORD-BIG\",\"amount\":10000.01}");

        assertThat(response.statusCode()).isEqualTo(422);
        JsonNode body = JSON.readTree(response.body());
        assertThat(body.get("status").asText()).isEqualTo("FAILED");
        assertThat(body.get("failureCode").asText()).isEqualTo(OrderPlugin.ORDER_REJECTED);
        assertThat(body.get("paymentId").isNull()).isTrue();
        assertThat(orderPlugin.paymentCount()).isEqualTo(paymentsBefore);
    }

    @Test
    void storesExecutionAndServesItBack() throws Exception {
        String executionId = JSON.readTree(post("/v1/orders", "{\"orderId\":\"ORD-2\",\"amount\":5}")
                        .body())
                .get("executionId")
                .asText();

        HttpResponse<String> response = get("/v1/executions/" + executionId);

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = JSON.readTree(response.body());
        assertThat(body.get("executionId").asText()).isEqualTo(executionId);
        assertThat(body.get("goal").asText()).isEqualTo(OrderWorkflowConfig.ORDER_GOAL);
        assertThat(body.get("state").asText()).isEqualTo("COMPLETED");
    }

    @Test
    void returnsErrorSchemaForUnknownExecution() throws Exception {
        HttpResponse<String> response = http.send(
                request("/v1/executions/no-such-id")
                        .header(ApiErrorHandler.TRACE_HEADER, "trace-123")
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(404);
        JsonNode error = JSON.readTree(response.body()).get("error");
        assertThat(error.get("code").asText()).isEqualTo("EXECUTION_NOT_FOUND");
        assertThat(error.get("trace_id").asText()).isEqualTo("trace-123");
    }

    @Test
    void rejectsInvalidOrderBeforeRunningAnything() throws Exception {
        HttpResponse<String> response = post("/v1/orders", "{\"orderId\":\"bad id!\",\"amount\":-1}");

        assertThat(response.statusCode()).isEqualTo(400);
        JsonNode error = JSON.readTree(response.body()).get("error");
        assertThat(error.get("code").asText()).isEqualTo("VALIDATION_FAILED");
        assertThat(error.get("trace_id").asText()).isNotBlank();
    }

    @Test
    void rejectsMalformedJson() throws Exception {
        HttpResponse<String> response = post("/v1/orders", "{not json");

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(JSON.readTree(response.body()).get("error").get("code").asText())
                .isEqualTo("MALFORMED_REQUEST");
    }

    @Test
    void readinessProbeIncludesNexoraHealth() throws Exception {
        HttpResponse<String> response = get("/actuator/health/readiness");

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = JSON.readTree(response.body());
        assertThat(body.get("status").asText()).isEqualTo("UP");
        JsonNode nexora = body.get("components").get("nexora");
        assertThat(nexora.get("status").asText()).isEqualTo("UP");
        assertThat(nexora.get("details").get("persistence").asText()).isEqualTo("UP");
        assertThat(nexora.get("details").get("activePlugins").get(0).asText()).isEqualTo(OrderPlugin.PLUGIN_ID);
    }

    private HttpResponse<String> post(String path, String json) throws IOException, InterruptedException {
        return http.send(
                request(path)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(json))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path) throws IOException, InterruptedException {
        return http.send(request(path).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(REQUEST_TIMEOUT);
    }
}
