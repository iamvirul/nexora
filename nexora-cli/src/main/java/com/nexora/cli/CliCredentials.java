package com.nexora.cli;

import com.nexora.api.auth.SecureFiles;
import java.io.IOException;
import java.io.Reader;
import java.io.StringWriter;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;
import java.util.Properties;

/**
 * User API keys saved by {@code nexora login}, one per observe server, in an owner-only file.
 * Servers are keyed by origin ({@code scheme://host:port}) so a trailing slash or default port
 * does not split one server into two entries.
 */
final class CliCredentials {

    static final String DEFAULT_FILE = "${sys:user.home}/.nexora/credentials";

    private final Path file;

    CliCredentials(Path file) {
        this.file = file;
    }

    Path file() {
        return file;
    }

    Optional<String> keyFor(URI server) {
        String key = load().getProperty(origin(server));
        return key == null || key.isBlank() ? Optional.empty() : Optional.of(key);
    }

    void save(URI server, String apiKey) throws IOException {
        Properties props = load();
        props.setProperty(origin(server), apiKey);
        write(props);
    }

    /** @return whether a key was stored for {@code server} */
    boolean remove(URI server) throws IOException {
        Properties props = load();
        boolean removed = props.remove(origin(server)) != null;
        if (removed) {
            write(props);
        }
        return removed;
    }

    static String origin(URI server) {
        String scheme = server.getScheme().toLowerCase(Locale.ROOT);
        int port = server.getPort() >= 0 ? server.getPort() : ("https".equals(scheme) ? 443 : 80);
        return scheme + "://" + server.getHost().toLowerCase(Locale.ROOT) + ":" + port;
    }

    private Properties load() {
        Properties props = new Properties();
        if (!Files.exists(file)) {
            return props;
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            props.load(reader);
            return props;
        } catch (IOException e) {
            throw new IllegalStateException("Could not read saved logins from " + file, e);
        }
    }

    private void write(Properties props) throws IOException {
        StringWriter out = new StringWriter();
        props.store(out, "Nexora user API keys from `nexora login`, one per observe server. Keep this file private.");
        SecureFiles.writeOwnerOnly(file, out.toString());
    }
}
