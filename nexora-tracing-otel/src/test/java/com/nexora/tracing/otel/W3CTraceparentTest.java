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
        assertThat(parsed.get().version()).isEqualTo("00");
        assertThat(parsed.get().traceId()).isEqualTo("1234567890abcdef1234567890abcdef");
        assertThat(parsed.get().spanId()).isEqualTo("fedcba0987654321");
        assertThat(parsed.get().sampled()).isTrue();
    }

    @Test
    void parseRejectsNullBlankAndMalformedHeaders() {
        assertThat(W3CTraceparent.parse(null)).isEmpty();
        assertThat(W3CTraceparent.parse("")).isEmpty();
        assertThat(W3CTraceparent.parse("not-a-traceparent")).isEmpty();
        assertThat(W3CTraceparent.parse("00-tooshort-fedcba0987654321-01")).isEmpty();
    }

    @Test
    void parseRejectsForbiddenVersionAndAllZeroIdentifiers() {
        assertThat(W3CTraceparent.parse(
                "ff-1234567890abcdef1234567890abcdef-fedcba0987654321-01")).isEmpty();
        assertThat(W3CTraceparent.parse(
                "00-00000000000000000000000000000000-fedcba0987654321-01")).isEmpty();
        assertThat(W3CTraceparent.parse(
                "00-1234567890abcdef1234567890abcdef-0000000000000000-01")).isEmpty();
    }

    @Test
    void parseSupportsHigherVersionsWithExtensions() {
        var parsed = W3CTraceparent.parse(
                "01-1234567890abcdef1234567890abcdef-fedcba0987654321-03-future-field");

        assertThat(parsed).isPresent();
        assertThat(parsed.get().version()).isEqualTo("01");
        assertThat(parsed.get().sampled()).isTrue();
    }

    @Test
    void parseRejectsExtensionsForVersionZeroAndInvalidHigherVersionBoundaries() {
        assertThat(W3CTraceparent.parse(
                "00-1234567890abcdef1234567890abcdef-fedcba0987654321-01-extra")).isEmpty();
        assertThat(W3CTraceparent.parse(
                "01-1234567890abcdef1234567890abcdef-fedcba0987654321-01extra")).isEmpty();
    }

    @Test
    void toTraceContextBuildsARootContextWithNoBaggage() {
        var parsed = new W3CTraceparent.Parsed(
                "00", "1234567890abcdef1234567890abcdef", "fedcba0987654321", false);

        TraceContext ctx = W3CTraceparent.toTraceContext(parsed);

        assertThat(ctx.traceId()).isEqualTo(parsed.traceId());
        assertThat(ctx.spanId()).isEqualTo(parsed.spanId());
        assertThat(ctx.parentSpanId()).isNull();
        assertThat(ctx.baggage()).isEmpty();
        assertThat(ctx.sampled()).isFalse();
        assertThat(ctx.childSpan().sampled()).isFalse();
    }

    @Test
    void formatPreservesUnsampledDecision() {
        TraceContext ctx = new TraceContext(
                "1234567890abcdef", "fedcba0987654321", null, Map.of(), false);

        assertThat(W3CTraceparent.format(ctx))
                .isEqualTo("00-1234567890abcdef1234567890abcdef-fedcba0987654321-00");
    }

    @Test
    void expandTraceIdIsIdempotentForAlreadyWideIds() {
        String wide = "1234567890abcdef1234567890abcdef";
        assertThat(W3CTraceparent.expandTraceId(wide)).isEqualTo(wide);
    }
}
