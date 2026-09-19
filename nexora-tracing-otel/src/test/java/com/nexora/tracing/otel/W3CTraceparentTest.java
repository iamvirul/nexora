package com.nexora.tracing.otel;

import com.nexora.core.context.TraceContext;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class W3CTraceparentTest {

    @Test
    void formatExpandsInternalTraceIdTo32Chars() {
        TraceContext ctx = new TraceContext("1234567890abcdef", "fedcba0987654321", null, Map.of());

        String header = W3CTraceparent.format(ctx);

        assertThat(header).isEqualTo("00-1234567890abcdef1234567890abcdef-fedcba0987654321-01");
    }

    @Test
    void parseRoundTripsAWellFormedHeader() {
        String header = "00-1234567890abcdef1234567890abcdef-fedcba0987654321-01";

        Optional<W3CTraceparent.Parsed> parsed = W3CTraceparent.parse(header);

        assertThat(parsed).isPresent();
        assertThat(parsed.get().traceId()).isEqualTo("1234567890abcdef1234567890abcdef");
        assertThat(parsed.get().spanId()).isEqualTo("fedcba0987654321");
    }

    @Test
    void parseRejectsNullBlankAndMalformedHeaders() {
        assertThat(W3CTraceparent.parse(null)).isEmpty();
        assertThat(W3CTraceparent.parse("")).isEmpty();
        assertThat(W3CTraceparent.parse("not-a-traceparent")).isEmpty();
        assertThat(W3CTraceparent.parse("00-tooshort-fedcba0987654321-01")).isEmpty();
    }

    @Test
    void toTraceContextBuildsARootContextWithNoBaggage() {
        var parsed = new W3CTraceparent.Parsed("1234567890abcdef1234567890abcdef", "fedcba0987654321");

        TraceContext ctx = W3CTraceparent.toTraceContext(parsed);

        assertThat(ctx.traceId()).isEqualTo(parsed.traceId());
        assertThat(ctx.spanId()).isEqualTo(parsed.spanId());
        assertThat(ctx.parentSpanId()).isNull();
        assertThat(ctx.baggage()).isEmpty();
    }

    @Test
    void expandTraceIdIsIdempotentForAlreadyWideIds() {
        String wide = "1234567890abcdef1234567890abcdef";
        assertThat(W3CTraceparent.expandTraceId(wide)).isEqualTo(wide);
    }
}
