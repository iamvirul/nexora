package com.nexora.cli;

import com.nexora.api.auth.PasswordHasher;
import com.nexora.api.auth.UserStore;
import java.io.BufferedReader;
import java.io.Console;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.ScopeType;
import picocli.CommandLine.Spec;

/**
 * Manages the observe dashboard's user accounts. Changes reach a running {@code nexora observe}
 * on its next request: no restart, and removing a user or changing a password ends their sessions.
 */
@Command(
        name = "user",
        // description loaded from help/user.help at startup via HelpLoader
        mixinStandardHelpOptions = true,
        subcommands = {
            UserCommand.Add.class,
            UserCommand.Passwd.class,
            UserCommand.Remove.class,
            UserCommand.ListUsers.class
        })
public class UserCommand implements Callable<Integer> {

    static final int EXIT_OK = 0;
    static final int EXIT_FAILED = 1;
    static final int EXIT_USAGE = 2;

    @Spec
    CommandSpec spec;

    @Option(
            names = {"--users-file"},
            scope = ScopeType.INHERIT,
            defaultValue = "${sys:user.home}/.nexora/observe-users",
            description = "Dashboard user file (must match nexora observe --users-file). Default: ${DEFAULT-VALUE}")
    Path usersFile;

    /** Where --password-stdin reads from; tests replace it. */
    InputStream in = System.in;

    @Override
    public Integer call() {
        spec.commandLine().usage(spec.commandLine().getOut());
        return EXIT_OK;
    }

    UserStore store() {
        return new UserStore(usersFile, new PasswordHasher());
    }

    /**
     * Prompts twice without echo, or reads one line from stdin for scripts. Never takes the password
     * as an argument, where it would show in the process list and shell history.
     *
     * @throws IllegalArgumentException if there is no terminal and no --password-stdin, or the entries differ
     */
    char[] readNewPassword(String username, boolean fromStdin) throws IOException {
        if (fromStdin) {
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line = reader.readLine();
            if (line == null || line.isEmpty()) {
                throw new IllegalArgumentException("No password on standard input");
            }
            return line.toCharArray();
        }
        Console console = System.console();
        if (console == null) {
            throw new IllegalArgumentException(
                    "No terminal to prompt for a password; pipe it in with --password-stdin");
        }
        char[] first = console.readPassword("New password for %s: ", username);
        char[] second = console.readPassword("Repeat the password: ");
        try {
            if (first == null || !Arrays.equals(first, second)) {
                throw new IllegalArgumentException("The two passwords do not match");
            }
            return first.clone();
        } finally {
            if (first != null) Arrays.fill(first, '\0');
            if (second != null) Arrays.fill(second, '\0');
        }
    }

    /** Runs {@code action}, mapping input problems to exit 2 and store problems to exit 1. */
    int run(Action action) {
        PrintWriter err = spec.commandLine().getErr();
        try {
            return action.run();
        } catch (IllegalArgumentException e) {
            err.println("Error: " + e.getMessage());
            return EXIT_USAGE;
        } catch (IllegalStateException | IOException e) {
            err.println("Error: " + e.getMessage());
            return EXIT_FAILED;
        }
    }

    @FunctionalInterface
    interface Action {
        int run() throws IOException;
    }

    @Command(name = "add", mixinStandardHelpOptions = true, description = "Create a dashboard user.")
    static final class Add implements Callable<Integer> {
        @ParentCommand
        UserCommand parent;

        @Spec
        CommandSpec spec;

        @Parameters(index = "0", paramLabel = "USERNAME", description = "3-32 characters: a-z, 0-9, '.', '_', '-'.")
        String username;

        @Option(names = "--password-stdin", description = "Read the password from the first line of standard input.")
        boolean passwordStdin;

        @Override
        public Integer call() {
            return parent.run(() -> {
                String name = UserStore.requireValidUsername(username);
                UserStore store = parent.store();
                if (store.usernames().contains(name)) {
                    throw new IllegalStateException("User '" + name + "' already exists; use `nexora user passwd`");
                }
                char[] password = parent.readNewPassword(name, passwordStdin);
                try {
                    store.add(name, password);
                } finally {
                    Arrays.fill(password, '\0');
                }
                spec.commandLine()
                        .getOut()
                        .printf("Added user '%s' to %s. It can log in now; no restart needed.%n", name, store.file());
                return EXIT_OK;
            });
        }
    }

    @Command(
            name = "passwd",
            mixinStandardHelpOptions = true,
            description = "Change a user's password; their sessions end.")
    static final class Passwd implements Callable<Integer> {
        @ParentCommand
        UserCommand parent;

        @Spec
        CommandSpec spec;

        @Parameters(index = "0", paramLabel = "USERNAME")
        String username;

        @Option(names = "--password-stdin", description = "Read the password from the first line of standard input.")
        boolean passwordStdin;

        @Override
        public Integer call() {
            return parent.run(() -> {
                String name = UserStore.normalize(username);
                UserStore store = parent.store();
                if (!store.usernames().contains(name)) {
                    throw new IllegalStateException("No user '" + name + "'");
                }
                char[] password = parent.readNewPassword(name, passwordStdin);
                try {
                    store.setPassword(name, password);
                } finally {
                    Arrays.fill(password, '\0');
                }
                spec.commandLine()
                        .getOut()
                        .printf("Changed the password for '%s'. Their open sessions have ended.%n", name);
                return EXIT_OK;
            });
        }
    }

    @Command(name = "remove", mixinStandardHelpOptions = true, description = "Delete a user; their sessions end.")
    static final class Remove implements Callable<Integer> {
        @ParentCommand
        UserCommand parent;

        @Spec
        CommandSpec spec;

        @Parameters(index = "0", paramLabel = "USERNAME")
        String username;

        @Override
        public Integer call() {
            return parent.run(() -> {
                String name = UserStore.normalize(username);
                UserStore store = parent.store();
                if (!store.usernames().contains(name)) {
                    throw new IllegalStateException("No user '" + name + "'");
                }
                store.remove(name);
                spec.commandLine().getOut().printf("Removed user '%s'. Their open sessions have ended.%n", name);
                return EXIT_OK;
            });
        }
    }

    @Command(name = "list", mixinStandardHelpOptions = true, description = "List dashboard users.")
    static final class ListUsers implements Callable<Integer> {
        @ParentCommand
        UserCommand parent;

        @Spec
        CommandSpec spec;

        @Override
        public Integer call() {
            return parent.run(() -> {
                List<String> names = parent.store().usernames();
                PrintWriter out = spec.commandLine().getOut();
                if (names.isEmpty()) {
                    out.println("No dashboard users. Add one with: nexora user add <username>");
                } else {
                    names.forEach(out::println);
                }
                return EXIT_OK;
            });
        }
    }
}
