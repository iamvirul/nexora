package com.nexora.cli;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.UnaryOperator;

/**
 * Which execution store the CLI opens, decided once from {@code nexora.json} and the environment.
 *
 * <p>{@value #JDBC_URL_ENV} names any supported database by JDBC URL, credentials included, so a
 * deployment can keep it in a secret (a Kubernetes Secret, for example). It is read only from the
 * environment, never from {@code nexora.json}, which is often stored as plain configuration.
 * {@code executionStore} in {@code nexora.json} still names an embedded H2 file. Setting both is a
 * mistake the CLI refuses rather than guesses at.
 */
sealed interface StoreSelection {

    String JDBC_URL_ENV = "NEXORA_JDBC_URL";

    /** Databases whose drivers the CLI ships and whose SQL the store supports. */
    Set<String> SUPPORTED_SUBPROTOCOLS = Set.of("postgresql", "h2");

    /** No persistence: executions live in memory only. */
    record None() implements StoreSelection {}

    /** The embedded H2 file at {@code path}, shared with other processes through H2's auto-server mode. */
    record H2File(String path) implements StoreSelection {}

    /** A database named by JDBC URL. The URL can hold a password, so it is never logged or printed. */
    record JdbcUrl(String url) implements StoreSelection {
        @Override
        public String toString() {
            return "JdbcUrl[" + subprotocol(url).orElse("jdbc") + "]";
        }
    }

    /**
     * @throws IllegalArgumentException if both are set, or the URL is not a supported JDBC URL
     */
    static StoreSelection resolve(CliConfig config, UnaryOperator<String> env) {
        String url = env.apply(JDBC_URL_ENV);
        boolean hasUrl = url != null && !url.isBlank();
        boolean hasFile = config.executionStore != null && !config.executionStore.isBlank();
        if (hasUrl && hasFile) {
            throw new IllegalArgumentException("Both " + JDBC_URL_ENV + " and executionStore in nexora.json are set;"
                    + " keep one: the JDBC URL for a database server, executionStore for an embedded H2 file");
        }
        if (hasUrl) {
            String trimmed = url.strip();
            String subprotocol = subprotocol(trimmed)
                    .orElseThrow(() -> new IllegalArgumentException(
                            JDBC_URL_ENV + " must be a JDBC URL such as jdbc:postgresql://host:5432/nexora"));
            if (!SUPPORTED_SUBPROTOCOLS.contains(subprotocol)) {
                throw new IllegalArgumentException(JDBC_URL_ENV + " uses jdbc:" + subprotocol
                        + ", which is not supported; use jdbc:postgresql or jdbc:h2");
            }
            return new JdbcUrl(trimmed);
        }
        return hasFile ? new H2File(config.executionStore) : new None();
    }

    /** The part after {@code jdbc:} and before the next colon, lower-cased; never anything past it. */
    private static Optional<String> subprotocol(String url) {
        String prefix = "jdbc:";
        if (!url.regionMatches(true, 0, prefix, 0, prefix.length())) {
            return Optional.empty();
        }
        int end = url.indexOf(':', prefix.length());
        return end <= prefix.length()
                ? Optional.empty()
                : Optional.of(url.substring(prefix.length(), end).toLowerCase(Locale.ROOT));
    }
}
