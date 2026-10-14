package com.nexora.cli;

import java.net.URI;
import java.net.http.HttpRequest;

/** A fixed API key for tests that exercise /api routes; the routes themselves stay fully protected. */
final class TestAuth {

    static final String KEY = "nxk_test-key-0123456789abcdefghijklmnopq";

    private TestAuth() {}

    static ApiAuth auth() {
        return new ApiAuth(ApiKeyStore.digest(KEY));
    }

    /** {@link HttpRequest#newBuilder(URI)} with the test key already attached. */
    static HttpRequest.Builder authorized(URI uri) {
        return HttpRequest.newBuilder(uri).header("Authorization", "Bearer " + KEY);
    }
}
