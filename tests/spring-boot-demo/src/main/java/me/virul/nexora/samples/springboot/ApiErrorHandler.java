package me.virul.nexora.samples.springboot;

import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;

/**
 * Turns failures into {@code {"error": {"code", "message", "trace_id"}}} without exposing stack
 * traces. The trace id is the caller's {@code X-Trace-ID} header when it is a plain id, or a new
 * one: the header is client input, and it is both logged and echoed back.
 */
@RestControllerAdvice
public class ApiErrorHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiErrorHandler.class);

    static final String TRACE_HEADER = "X-Trace-ID";

    // Letters, digits and dashes only: covers UUIDs and W3C trace ids, and cannot break a log line.
    private static final Pattern SAFE_TRACE_ID = Pattern.compile("[A-Za-z0-9-]{1,64}");

    public record ErrorBody(Error error) {
        public record Error(String code, String message, String trace_id) {}
    }

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ErrorBody> apiError(ApiException e, WebRequest request) {
        return body(e.status(), e.code(), e.getMessage(), request.getHeader(TRACE_HEADER));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ErrorBody> unreadable(WebRequest request) {
        return body(
                HttpStatus.BAD_REQUEST,
                "MALFORMED_REQUEST",
                "Request body is not valid JSON for this endpoint",
                request.getHeader(TRACE_HEADER));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorBody> unexpected(Exception e, WebRequest request) {
        String id = traceIdOrNew(request.getHeader(TRACE_HEADER));
        log.error("Request failed trace_id={}", id, e);
        return body(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "The request could not be completed", id);
    }

    private static ResponseEntity<ErrorBody> body(HttpStatus status, String code, String message, String traceId) {
        return ResponseEntity.status(status)
                .body(new ErrorBody(new ErrorBody.Error(code, message, traceIdOrNew(traceId))));
    }

    private static String traceIdOrNew(String traceId) {
        return traceId != null && SAFE_TRACE_ID.matcher(traceId).matches()
                ? traceId
                : UUID.randomUUID().toString();
    }
}
