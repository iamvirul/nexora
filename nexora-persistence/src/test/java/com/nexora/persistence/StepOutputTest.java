package com.nexora.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class StepOutputTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void noOutputIsAnEmptyMap() {
        StepOutput output = StepOutput.capture(null, StepOutput.DEFAULT_MAX_BYTES);

        assertEquals(Map.of(), output.value());
        assertFalse(output.truncated());
    }

    @Test
    void mapOutputIsKeptAsItsJsonForm() {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("paymentId", "p-1");
        raw.put("amount", 42);
        raw.put("tags", List.of("a", "b"));
        raw.put("note", null);

        StepOutput output = StepOutput.capture(raw, StepOutput.DEFAULT_MAX_BYTES);

        assertEquals(raw, output.value());
        assertFalse(output.truncated());
    }

    @Test
    void nonMapOutputIsStoredUnderValue() {
        StepOutput output = StepOutput.capture("charged", StepOutput.DEFAULT_MAX_BYTES);

        assertEquals(Map.of(StepOutput.VALUE_KEY, "charged"), output.value());
    }

    @Test
    void instantsAreWrittenAsIsoStrings() {
        Instant at = Instant.parse("2026-10-14T10:00:00Z");

        StepOutput output = StepOutput.capture(Map.of("at", at), StepOutput.DEFAULT_MAX_BYTES);

        assertEquals(Map.of("at", "2026-10-14T10:00:00Z"), output.value());
    }

    @Test
    void capturedValueDoesNotChangeWhenTheCapabilityMutatesItsMap() {
        Map<String, Object> raw = new HashMap<>(Map.of("count", 1));

        StepOutput output = StepOutput.capture(raw, StepOutput.DEFAULT_MAX_BYTES);
        raw.put("count", 2);

        assertEquals(Map.of("count", 1), output.value());
        assertThrows(UnsupportedOperationException.class, () -> output.value().put("x", 1));
    }

    @Test
    void oversizedOutputIsTruncatedToAPreviewWithinTheCap() throws Exception {
        int maxBytes = 1024;
        String big = "x".repeat(10_000);

        StepOutput output = StepOutput.capture(Map.of("blob", big), maxBytes);

        assertTrue(output.truncated());
        assertTrue(output.encodedBytes() > 10_000, "encodedBytes reports the full size");
        String preview = (String) output.value().get(StepOutput.PREVIEW_KEY);
        assertTrue(preview.startsWith("{\"blob\":\"xxx"), preview);
        assertTrue(encodedSize(output.value()) <= maxBytes);
        assertTrue(encodedSize(output.value()) > maxBytes - 64, "the preview uses most of the budget");
    }

    @Test
    void previewOfEscapeHeavyAndMultiByteOutputStillFitsTheCap() throws Exception {
        int maxBytes = 512;
        // Quotes double when escaped; the emoji is a surrogate pair of 4 UTF-8 bytes.
        String nasty = "\"é😀\n".repeat(2_000);

        StepOutput output = StepOutput.capture(Map.of("text", nasty), maxBytes);

        assertTrue(output.truncated());
        String preview = (String) output.value().get(StepOutput.PREVIEW_KEY);
        assertFalse(Character.isHighSurrogate(preview.charAt(preview.length() - 1)), "no split surrogate pair");
        assertTrue(encodedSize(output.value()) <= maxBytes, "size " + encodedSize(output.value()));
    }

    @Test
    void outputExactlyAtTheCapIsKept() throws Exception {
        Map<String, Object> raw = Map.of("k", "v".repeat(300));
        int exact = encodedSize(raw);

        assertFalse(StepOutput.capture(raw, exact).truncated());
        assertTrue(StepOutput.capture(raw, exact - 1).truncated());
    }

    @Test
    void zeroCapRecordsNothing() {
        StepOutput output = StepOutput.capture(Map.of("secret", "s"), 0);

        assertEquals(Map.of(), output.value());
        assertFalse(output.truncated());
    }

    @Test
    void capBelowTheMinimumIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> StepOutput.capture("x", StepOutput.MIN_MAX_BYTES - 1));
        assertThrows(IllegalArgumentException.class, () -> StepOutput.capture("x", -1));
    }

    @Test
    void outputThatCannotBeWrittenAsJsonIsRejectedNamingItsType() {
        Map<String, Object> cyclic = new HashMap<>();
        cyclic.put("self", cyclic);

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class, () -> StepOutput.capture(cyclic, StepOutput.DEFAULT_MAX_BYTES));

        assertTrue(error.getMessage().contains("java.util.HashMap"), error.getMessage());
    }

    private static int encodedSize(Object value) throws Exception {
        return JSON.writeValueAsString(value).getBytes(StandardCharsets.UTF_8).length;
    }
}
