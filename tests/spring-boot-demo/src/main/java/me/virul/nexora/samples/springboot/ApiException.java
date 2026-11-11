package me.virul.nexora.samples.springboot;

import java.util.Objects;
import org.springframework.http.HttpStatus;

/** A request failure with the HTTP status and error code the client is shown. */
public final class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public ApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = Objects.requireNonNull(status, "status must not be null");
        this.code = Objects.requireNonNull(code, "code must not be null");
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }
}
