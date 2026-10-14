package com.nexora.api.auth;

import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/** Writes the auth layer's own responses; never cached, since they concern credentials. */
final class Responses {

    static final String JSON = "application/json; charset=utf-8";
    static final String TEXT = "text/plain; charset=utf-8";

    private Responses() {}

    static void send(HttpExchange exchange, int status, String contentType, String body) throws IOException {
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, payload.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(payload);
        } finally {
            exchange.close();
        }
    }
}
