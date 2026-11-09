package com.nexora.cli;

import java.util.regex.Pattern;

/**
 * The engine could not start from the given configuration, such as a store that cannot be opened.
 * Printed as one {@code Error:} line with exit code 2, never as a stack trace: the causes of a
 * store failure can quote the JDBC URL, and with it a database host, user or password.
 */
final class EngineStartupException extends RuntimeException {

    /** A JDBC URL up to the next whitespace or quote; reduced to its subprotocol before printing. */
    private static final Pattern JDBC_URL = Pattern.compile("(?i)(jdbc:[a-z0-9]+)(:[^\\s\"']*)?");

    private static final int MAX_DEPTH = 10;

    private EngineStartupException(String message) {
        super(message, null, false, false);
    }

    /**
     * The failure's own message plus the innermost cause's, which usually names the reason
     * ("Connection refused"), with every JDBC URL in either cut to {@code jdbc:<subprotocol>}.
     */
    static EngineStartupException of(RuntimeException failure) {
        String message = redact(String.valueOf(failure.getMessage()));
        Throwable root = rootCause(failure);
        if (root != failure && root.getMessage() != null) {
            String reason = redact(root.getMessage());
            if (!message.contains(reason)) {
                message = message + " (" + reason + ")";
            }
        }
        return new EngineStartupException(message);
    }

    static String redact(String text) {
        return JDBC_URL.matcher(text).replaceAll("$1");
    }

    private static Throwable rootCause(Throwable failure) {
        Throwable current = failure;
        for (int depth = 0; depth < MAX_DEPTH && current.getCause() != null && current.getCause() != current; depth++) {
            current = current.getCause();
        }
        return current;
    }
}
