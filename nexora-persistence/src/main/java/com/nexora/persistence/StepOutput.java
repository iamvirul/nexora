package com.nexora.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A step's output in the form it is stored and shown: a JSON object no larger than a size cap.
 *
 * <p>A capability may return any object. A {@link Map} is kept as its JSON form; any other value
 * is put under {@link #VALUE_KEY}, so the stored form is always an object. When that JSON is
 * larger than the cap, the value becomes a single {@link #PREVIEW_KEY} entry holding the start of
 * the JSON text and {@link #truncated()} is true. Truncation only affects what is recorded: later
 * steps in the same execution still receive the full output.
 *
 * @param value        the recorded output, unmodifiable; empty when the step produced none
 * @param truncated    whether {@code value} is a preview of a larger output
 * @param encodedBytes size of the full output as UTF-8 JSON, before any truncation
 */
public record StepOutput(Map<String, Object> value, boolean truncated, int encodedBytes) {

    public static final int DEFAULT_MAX_BYTES = 64 * 1024;
    /** Smallest non-zero cap: leaves room for the preview entry and a prefix worth reading. */
    public static final int MIN_MAX_BYTES = 256;

    public static final String VALUE_KEY = "value";
    public static final String PREVIEW_KEY = "preview";
    public static final StepOutput NONE = new StepOutput(Map.of(), false, 0);

    // ISO-8601 strings for java.time values, so an output holding an Instant is readable rather than rejected.
    private static final ObjectMapper JSON = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private static final TypeReference<LinkedHashMap<String, Object>> MAP_TYPE = new TypeReference<>() {};
    // An escape is at most 6 chars (backslash-u plus 4 hex digits) for one input char.
    private static final int MAX_ESCAPED_CHAR_BYTES = 6;

    public StepOutput {
        // Not Map.copyOf: JSON objects may hold null values.
        value = value == null || value.isEmpty() ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(value));
    }

    /**
     * Captures a step's output for recording.
     *
     * @param output   what the capability returned; {@code null} means no output
     * @param maxBytes the cap on the recorded JSON in UTF-8 bytes; {@code 0} records no output at all
     * @throws IllegalArgumentException if {@code maxBytes} is invalid (see {@link #validateMaxBytes}),
     *                                  or the output cannot be written as JSON
     */
    public static StepOutput capture(Object output, int maxBytes) {
        validateMaxBytes(maxBytes);
        if (output == null || maxBytes == 0) {
            return NONE;
        }
        Object document = output instanceof Map<?, ?> ? output : Collections.singletonMap(VALUE_KEY, output);
        String json = write(document, output);
        int bytes = utf8Length(json);
        if (bytes <= maxBytes) {
            return new StepOutput(read(json), false, bytes);
        }
        return new StepOutput(Map.of(PREVIEW_KEY, preview(json, maxBytes)), true, bytes);
    }

    /** @throws IllegalArgumentException unless {@code maxBytes} is 0 or at least {@link #MIN_MAX_BYTES} */
    public static int validateMaxBytes(int maxBytes) {
        if (maxBytes != 0 && maxBytes < MIN_MAX_BYTES) {
            throw new IllegalArgumentException("stepOutputMaxBytes must be 0 (record no output) or at least "
                    + MIN_MAX_BYTES + ", got " + maxBytes);
        }
        return maxBytes;
    }

    /** The longest prefix of {@code json} whose preview entry, written as JSON, fits in {@code maxBytes}. */
    private static String preview(String json, int maxBytes) {
        int end = Math.min(json.length(), maxBytes);
        while (true) {
            String prefix = prefix(json, end);
            int overflow = utf8Length(write(Map.of(PREVIEW_KEY, prefix), prefix)) - maxBytes;
            if (overflow <= 0) {
                return prefix;
            }
            // Each char costs at most MAX_ESCAPED_CHAR_BYTES, so this never cuts more than it must.
            end = Math.max(
                    0, prefix.length() - Math.max(1, (overflow + MAX_ESCAPED_CHAR_BYTES - 1) / MAX_ESCAPED_CHAR_BYTES));
        }
    }

    /** Cuts at {@code end} chars, backing off one so a surrogate pair is never split. */
    private static String prefix(String text, int end) {
        int cut = end > 0 && Character.isHighSurrogate(text.charAt(end - 1)) ? end - 1 : end;
        return text.substring(0, cut);
    }

    private static String write(Object document, Object output) {
        try {
            return JSON.writeValueAsString(document);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(
                    "Step output of type " + output.getClass().getName() + " cannot be written as JSON: "
                            + e.getOriginalMessage(),
                    e);
        }
    }

    private static Map<String, Object> read(String json) {
        try {
            return JSON.readValue(json, MAP_TYPE);
        } catch (JsonProcessingException e) {
            // Unreachable: the text was just written by the same mapper.
            throw new IllegalStateException("Could not read back step output JSON", e);
        }
    }

    private static int utf8Length(String text) {
        return text.getBytes(StandardCharsets.UTF_8).length;
    }
}
