package com.nexora.planner.llm;

/** An LLM call that produced no usable answer. The planner declines and the next planner runs. */
public final class LlmCallException extends RuntimeException {

    /** Why the call failed, for logs and metrics. */
    public enum Reason {
        /** Bad key, no permission, or an unknown model: retrying will not help. */
        REJECTED,
        /** Rate limited, overloaded or a server error, after the SDK's own retries. */
        UNAVAILABLE,
        /** No connection, or the request timed out. */
        NETWORK,
        /** The model declined to answer. */
        REFUSED,
        /** The answer hit the token limit before the JSON was complete. */
        TRUNCATED,
        /** The response had no text to parse. */
        EMPTY
    }

    private final Reason reason;

    public LlmCallException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public LlmCallException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
