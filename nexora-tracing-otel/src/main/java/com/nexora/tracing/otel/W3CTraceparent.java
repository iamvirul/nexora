package com.nexora.tracing.otel;

import com.nexora.core.context.TraceContext;

import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Formats and parses the W3C Trace Context {@code traceparent} header
 * (https://www.w3.org/TR/trace-context/#traceparent-header), for HTTP
 * boundaries that propagate {@link TraceContext} in and out of the engine.
 *
 * <p>{@link TraceContext#traceId()} is normally 16 hex chars (64-bit), but
 * the W3C format requires a 32 hex char (128-bit) trace id. {@link #expandTraceId}
 * deterministically widens a 16-char id so every span in one execution still
 * shares the same W3C trace id; a trace id already 32 chars (e.g. one
 * received from an upstream caller) is passed through unchanged.
 */
public final class W3CTraceparent {

    private static final String VERSION = "00";
    private static final String TRACE_FLAGS_SAMPLED = "01";

    private static final Pattern TRACEPARENT_PATTERN =
            Pattern.compile("^[0-9a-f]{2}-([0-9a-f]{32})-([0-9a-f]{16})-[0-9a-f]{2}$");

    private W3CTraceparent() {}

    public record Parsed(String traceId, String spanId) {}

    /** Renders {@code context} as a {@code traceparent} header value. */
    public static String format(TraceContext context) {
        return VERSION + "-" + expandTraceId(context.traceId()) + "-" + context.spanId() + "-" + TRACE_FLAGS_SAMPLED;
    }

    /**
     * Parses a {@code traceparent} header value. Returns {@link Optional#empty()}
     * for a null, blank, or malformed header rather than throwing — callers are
     * expected to fall back to generating a fresh root {@link TraceContext}.
     */
    public static Optional<Parsed> parse(String header) {
        if (header == null || header.isBlank()) {
            return Optional.empty();
        }
        var matcher = TRACEPARENT_PATTERN.matcher(header.trim());
        if (!matcher.matches()) {
            return Optional.empty();
        }
        return Optional.of(new Parsed(matcher.group(1), matcher.group(2)));
    }

    /** Builds a root {@link TraceContext} from a parsed traceparent, with no baggage. */
    public static TraceContext toTraceContext(Parsed parsed) {
        return new TraceContext(parsed.traceId(), parsed.spanId(), null, Map.of());
    }

    /** Widens a trace id to the 32 hex chars the W3C format requires. Idempotent for already-32-char ids. */
    static String expandTraceId(String traceId) {
        if (traceId.length() >= 32) {
            return traceId.substring(0, 32);
        }
        StringBuilder sb = new StringBuilder(32);
        while (sb.length() < 32) {
            sb.append(traceId);
        }
        return sb.substring(0, 32);
    }
}
