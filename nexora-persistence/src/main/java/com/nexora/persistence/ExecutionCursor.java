package com.nexora.persistence;

import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;

/**
 * Position in the execution history, ordered by {@code started_at DESC, execution_id DESC}.
 * The next page holds executions strictly after this position in that order.
 *
 * <p>Keyset pagination instead of offsets keeps pages stable while new executions are written:
 * rows inserted at the head never shift what the next page returns.
 *
 * <p>{@link #encode()} produces an opaque URL-safe token for API clients; {@link #decode(String)}
 * treats its input as untrusted and rejects anything malformed.
 */
public record ExecutionCursor(Instant startedAt, String executionId) {

    private static final String SEPARATOR = "|";
    private static final int MAX_TOKEN_LENGTH = 256;

    public ExecutionCursor {
        Objects.requireNonNull(startedAt, "startedAt must not be null");
        Objects.requireNonNull(executionId, "executionId must not be null");
    }

    /** Cursor pointing just past {@code record}. */
    public static ExecutionCursor after(ExecutionRecord record) {
        return new ExecutionCursor(record.startedAt(), record.executionId());
    }

    public String encode() {
        String raw = startedAt + SEPARATOR + executionId;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * @throws IllegalArgumentException if {@code token} was not produced by {@link #encode()}
     */
    public static ExecutionCursor decode(String token) {
        if (token == null || token.isBlank() || token.length() > MAX_TOKEN_LENGTH) {
            throw new IllegalArgumentException("Invalid cursor");
        }
        try {
            String raw = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
            int split = raw.indexOf(SEPARATOR);
            if (split <= 0 || split == raw.length() - 1) {
                throw new IllegalArgumentException("Invalid cursor");
            }
            return new ExecutionCursor(Instant.parse(raw.substring(0, split)), raw.substring(split + 1));
        } catch (DateTimeException | IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid cursor", e);
        }
    }
}
