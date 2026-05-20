package com.nexora.cli;

import picocli.CommandLine;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Loads help text from {@code help/<command-name>.help} classpath resources
 * and injects it into each command's usage message at startup.
 *
 * This keeps help text out of annotation strings and in editable plain-text files.
 * The file name must match the PicoCLI command name exactly (case-sensitive).
 */
final class HelpLoader {

    private HelpLoader() {}

    /**
     * Walks the root command and all subcommands, loading a {@code .help} file
     * for each one that has a matching resource on the classpath.
     */
    static void apply(CommandLine root) {
        applyToCommand(root);
        for (CommandLine sub : root.getSubcommands().values()) {
            applyToCommand(sub);
        }
    }

    private static void applyToCommand(CommandLine cmd) {
        String name = cmd.getCommandName();
        String resource = "help/" + name + ".help";

        try (InputStream is = HelpLoader.class.getClassLoader().getResourceAsStream(resource)) {
            if (is == null) return;

            String raw = new String(is.readAllBytes(), StandardCharsets.UTF_8);

            // Split on newlines, preserve blank lines as empty strings (PicoCLI renders them as blank lines)
            String[] lines = raw.split("\n", -1);

            // Strip trailing empty line that comes from a newline at end of file
            if (lines.length > 0 && lines[lines.length - 1].isBlank()) {
                String[] trimmed = new String[lines.length - 1];
                System.arraycopy(lines, 0, trimmed, 0, trimmed.length);
                lines = trimmed;
            }

            cmd.getCommandSpec().usageMessage().description(lines);

        } catch (IOException e) {
            // Non-fatal: fall back to whatever the @Command annotation declared
        }
    }
}
