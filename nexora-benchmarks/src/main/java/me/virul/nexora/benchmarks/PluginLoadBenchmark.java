package me.virul.nexora.benchmarks;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;
import me.virul.nexora.event.InProcessEventBus;
import me.virul.nexora.loader.PluginManager;
import me.virul.nexora.registry.DefaultCapabilityRegistry;
import me.virul.nexora.spi.NexoraPlugin;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Loading a plugin JAR, activating it and deactivating it again through {@link PluginManager}: opening
 * the JAR in its own class loader, the {@link java.util.ServiceLoader} lookup, initialisation,
 * capability registration, and the release that closes the class loader. The JAR holds
 * {@link JarPlugin} and its nested classes, which the child-first plugin class loader defines from
 * the JAR on every load, so class definition is part of the cost.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(1)
public class PluginLoadBenchmark {

    private Path directory;
    private Path jar;
    private PluginManager manager;
    private DefaultCapabilityRegistry registry;

    /**
     * Writes the plugin JAR to a temporary directory.
     *
     * @throws IOException if the JAR cannot be written
     */
    @Setup
    public void setUp() throws IOException {
        directory = Files.createTempDirectory("nexora-bench-plugin");
        jar = writePluginJar(directory.resolve("bench-plugin.jar"));
        registry = new DefaultCapabilityRegistry();
        manager = new PluginManager(registry, new InProcessEventBus(Runnable::run));
    }

    /**
     * Loads, activates and deactivates the plugin once.
     *
     * @return the plugin id, consumed by JMH
     */
    @Benchmark
    public String loadActivateDeactivate() {
        String pluginId = manager.loadPlugin(jar);
        manager.activatePlugin(pluginId);
        manager.deactivatePlugin(pluginId);
        return pluginId;
    }

    /**
     * Deletes the temporary directory.
     *
     * @throws IOException if it cannot be deleted
     */
    @TearDown
    public void tearDown() throws IOException {
        try (Stream<Path> paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(PluginLoadBenchmark::delete);
        }
    }

    DefaultCapabilityRegistry registry() {
        return registry;
    }

    static Path writePluginJar(Path target) throws IOException {
        try (OutputStream out = Files.newOutputStream(target);
                JarOutputStream jarOut = new JarOutputStream(out)) {
            jarOut.putNextEntry(new JarEntry("META-INF/services/" + NexoraPlugin.class.getName()));
            jarOut.write(JarPlugin.class.getName().getBytes(StandardCharsets.UTF_8));
            jarOut.closeEntry();
            for (Class<?> type : JarPlugin.class.getNestMembers()) {
                writeClass(jarOut, type);
            }
        }
        return target;
    }

    private static void writeClass(JarOutputStream jarOut, Class<?> type) throws IOException {
        String entry = type.getName().replace('.', '/') + ".class";
        try (InputStream classBytes = type.getClassLoader().getResourceAsStream(entry)) {
            if (classBytes == null) {
                throw new IOException("Class file not found on the class path: " + entry);
            }
            jarOut.putNextEntry(new JarEntry(entry));
            classBytes.transferTo(jarOut);
            jarOut.closeEntry();
        }
    }

    private static void delete(Path path) {
        try {
            Files.delete(path);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not delete " + path, e);
        }
    }
}
