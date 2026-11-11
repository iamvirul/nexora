package me.virul.nexora.loader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import me.virul.nexora.core.capability.CapabilityRequest;
import me.virul.nexora.core.capability.CapabilityResult;
import me.virul.nexora.core.context.TraceContext;
import me.virul.nexora.event.ExecutionEvent;
import me.virul.nexora.event.InProcessEventBus;
import me.virul.nexora.event.PluginReloadFailedEvent;
import me.virul.nexora.event.PluginReloadedEvent;
import me.virul.nexora.registry.DefaultCapabilityRegistry;
import me.virul.nexora.spi.CapabilityDescriptor;
import me.virul.nexora.spi.CapabilityRegistry;
import me.virul.nexora.spi.PluginInitializationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PluginReloadTest {

    @TempDir
    Path dir;

    private CapabilityRegistry registry;
    private PluginManager manager;
    private final List<ExecutionEvent> events = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() throws IOException {
        ReloadFixtures.reset();
        registry = new DefaultCapabilityRegistry();
        InProcessEventBus bus = new InProcessEventBus(Runnable::run);
        bus.subscribeSync(PluginReloadedEvent.class, events::add);
        bus.subscribeSync(PluginReloadFailedEvent.class, events::add);
        manager = new PluginManager(registry, bus);
        String id = manager.loadPlugin(ReloadFixtures.jarFor(dir, "v1.jar", ReloadFixtures.V1.class));
        manager.activatePlugin(id);
    }

    @Test
    void reloadRoutesNewInvocationsToTheNewVersion() throws IOException {
        PluginReloadResult result =
                manager.reloadPlugin(ReloadFixtures.PLUGIN_ID, jar("v2.jar", ReloadFixtures.V2.class));

        assertEquals(new PluginReloadResult(ReloadFixtures.PLUGIN_ID, "1.0.0", "2.0.0"), result);
        assertEquals("2.0.0", invoke(ReloadFixtures.ECHO).output());
        assertEquals(PluginLifecycle.ACTIVE, manager.getLifecycle(ReloadFixtures.PLUGIN_ID));
        assertEquals("2.0.0", manager.version(ReloadFixtures.PLUGIN_ID).orElseThrow());
    }

    @Test
    void reloadPublishesEventWithBothVersions() throws IOException {
        manager.reloadPlugin(ReloadFixtures.PLUGIN_ID, jar("v2.jar", ReloadFixtures.V2.class));

        PluginReloadedEvent event = assertInstanceOf(PluginReloadedEvent.class, events.get(0));
        assertEquals(ReloadFixtures.PLUGIN_ID, event.pluginId());
        assertEquals("1.0.0", event.previousVersion());
        assertEquals("2.0.0", event.newVersion());
    }

    @Test
    void reloadRemovesDroppedCapabilitiesAndAddsNewOnes() throws IOException {
        manager.reloadPlugin(ReloadFixtures.PLUGIN_ID, jar("v2.jar", ReloadFixtures.V2.class));

        assertEquals(Set.of(ReloadFixtures.ECHO, ReloadFixtures.ADDED), capabilityIds());
    }

    @Test
    void reloadReplacesPluginPlanners() throws IOException {
        List<?> before = manager.registeredPlanners();

        manager.reloadPlugin(ReloadFixtures.PLUGIN_ID, jar("v2.jar", ReloadFixtures.V2.class));

        assertEquals(Set.of("planner-2.0.0"), plannerIds());
        assertFalse(before == manager.registeredPlanners(), "planner snapshot must be replaced on reload");
    }

    @Test
    void reloadShutsDownOldVersionAtOnceWhenNothingIsInFlight() throws IOException {
        manager.reloadPlugin(ReloadFixtures.PLUGIN_ID, jar("v2.jar", ReloadFixtures.V2.class));

        assertEquals(1, ReloadFixtures.shutdowns("1.0.0"));
        assertEquals(0, ReloadFixtures.shutdowns("2.0.0"));
    }

    @Test
    void inFlightInvocationFinishesOnOldVersionBeforeItIsReleased() throws Exception {
        CountDownLatch gate = new CountDownLatch(1);
        ReloadFixtures.v1Entered = new CountDownLatch(1);
        ReloadFixtures.v1Gate = gate;
        CompletableFuture<CapabilityResult> inFlight = CompletableFuture.supplyAsync(() -> invoke(ReloadFixtures.ECHO));
        assertTrue(ReloadFixtures.v1Entered.await(5, TimeUnit.SECONDS), "v1 invocation never started");

        manager.reloadPlugin(ReloadFixtures.PLUGIN_ID, jar("v2.jar", ReloadFixtures.V2.class));

        assertEquals("2.0.0", invoke(ReloadFixtures.ECHO).output(), "new invocations must use v2 at once");
        assertEquals(0, ReloadFixtures.shutdowns("1.0.0"), "v1 must stay up while an invocation runs on it");
        assertFalse(inFlight.isDone());

        gate.countDown();

        assertEquals("1.0.0", inFlight.get(5, TimeUnit.SECONDS).output());
        assertEquals(1, ReloadFixtures.shutdowns("1.0.0"));
    }

    @Test
    void staleReferenceToReleasedVersionIsHandedToCurrentVersion() throws IOException {
        var stale = registry.find(ReloadFixtures.ECHO).orElseThrow();

        manager.reloadPlugin(ReloadFixtures.PLUGIN_ID, jar("v2.jar", ReloadFixtures.V2.class));

        assertEquals("2.0.0", stale.execute(request(ReloadFixtures.ECHO)).output());
    }

    @Test
    void staleReferenceToRemovedCapabilityFailsAsNotFound() throws IOException {
        var stale = registry.find(ReloadFixtures.LEGACY).orElseThrow();

        manager.reloadPlugin(ReloadFixtures.PLUGIN_ID, jar("v2.jar", ReloadFixtures.V2.class));

        CapabilityResult result = stale.execute(request(ReloadFixtures.LEGACY));
        assertFalse(result.succeeded());
        assertEquals(LeasedCapability.NOT_FOUND_CODE, result.failureCode());
    }

    @Test
    void successiveReloadsEachReleaseTheirPredecessor() throws IOException {
        manager.reloadPlugin(ReloadFixtures.PLUGIN_ID, jar("v2.jar", ReloadFixtures.V2.class));
        manager.reloadPlugin(ReloadFixtures.PLUGIN_ID, jar("v3.jar", ReloadFixtures.V3.class));

        assertEquals("3.0.0", invoke(ReloadFixtures.ECHO).output());
        assertEquals(1, ReloadFixtures.shutdowns("1.0.0"));
        assertEquals(1, ReloadFixtures.shutdowns("2.0.0"));
    }

    @Test
    void reloadFromJarDeclaringAnotherIdRollsBack() throws IOException {
        Path other = jar("other.jar", ReloadFixtures.OtherId.class);

        PluginInitializationException error = assertThrows(
                PluginInitializationException.class, () -> manager.reloadPlugin(ReloadFixtures.PLUGIN_ID, other));

        assertTrue(error.getMessage().contains("someone-else"), error.getMessage());
        assertStillOnVersionOne();
    }

    @Test
    void reloadWhoseInitializeThrowsRollsBackAndShutsDownTheCandidate() throws IOException {
        Path broken = jar("broken.jar", ReloadFixtures.BrokenInit.class);

        PluginInitializationException error = assertThrows(
                PluginInitializationException.class, () -> manager.reloadPlugin(ReloadFixtures.PLUGIN_ID, broken));

        assertTrue(error.getMessage().contains("database unreachable"), error.getMessage());
        assertEquals(1, ReloadFixtures.shutdowns("4.0.0-broken"));
        assertStillOnVersionOne();
    }

    @Test
    void reloadFailurePublishesEventNamingTheVersionStillActive() throws IOException {
        Path broken = jar("broken.jar", ReloadFixtures.BrokenInit.class);

        assertThrows(PluginInitializationException.class, () -> manager.reloadPlugin(ReloadFixtures.PLUGIN_ID, broken));

        PluginReloadFailedEvent event = assertInstanceOf(PluginReloadFailedEvent.class, events.get(0));
        assertEquals(ReloadFixtures.PLUGIN_ID, event.pluginId());
        assertEquals("1.0.0", event.activeVersion());
        assertTrue(event.reason().contains("database unreachable"), event.reason());
        assertEquals(1, events.size(), "no PluginReloadedEvent on failure");
    }

    @Test
    void reloadFromJarWithoutPluginRollsBack() throws IOException {
        Path empty = ReloadFixtures.emptyJar(dir, "empty.jar");

        assertThrows(PluginInitializationException.class, () -> manager.reloadPlugin(ReloadFixtures.PLUGIN_ID, empty));

        assertStillOnVersionOne();
    }

    @Test
    void reloadFromMissingFileRollsBack() {
        Path missing = dir.resolve("does-not-exist.jar");

        PluginInitializationException error = assertThrows(
                PluginInitializationException.class, () -> manager.reloadPlugin(ReloadFixtures.PLUGIN_ID, missing));

        assertTrue(error.getMessage().contains("not a readable file"), error.getMessage());
        assertStillOnVersionOne();
    }

    @Test
    void reloadNeedingInactivePluginRollsBack() throws IOException {
        Path needsMore = jar("needs.jar", ReloadFixtures.MissingDependency.class);

        assertThrows(
                PluginInitializationException.class, () -> manager.reloadPlugin(ReloadFixtures.PLUGIN_ID, needsMore));

        assertStillOnVersionOne();
    }

    @Test
    void reloadTakingAnotherPluginsCapabilityRollsBackAndLeavesItUntouched() throws IOException {
        CapabilityDescriptor taken = new CapabilityDescriptor(
                ReloadFixtures.Conflicting.TAKEN, "owned elsewhere", List.of(), List.of(), true, false);
        registry.register(taken, request -> CapabilityResult.success("other"));
        Path conflicting = jar("conflict.jar", ReloadFixtures.Conflicting.class);

        assertThrows(
                PluginInitializationException.class, () -> manager.reloadPlugin(ReloadFixtures.PLUGIN_ID, conflicting));

        assertEquals("other", invoke(ReloadFixtures.Conflicting.TAKEN).output());
        assertEquals(1, ReloadFixtures.shutdowns("6.0.0"));
        assertStillOnVersionOne();
    }

    @Test
    void reloadOfUnknownPluginIsRejected() throws IOException {
        Path v2 = jar("v2.jar", ReloadFixtures.V2.class);

        assertThrows(IllegalStateException.class, () -> manager.reloadPlugin("nobody", v2));
        assertTrue(events.isEmpty());
    }

    @Test
    void reloadOfPluginRegisteredInCodeIsRejected() throws IOException {
        manager.registerPlugin(new ReloadFixtures.OtherId());
        manager.activatePlugin("someone-else");
        Path v2 = jar("v2.jar", ReloadFixtures.V2.class);

        IllegalStateException error =
                assertThrows(IllegalStateException.class, () -> manager.reloadPlugin("someone-else", v2));

        assertTrue(error.getMessage().contains("registered in code"), error.getMessage());
    }

    @Test
    void reloadOfLoadedButInactivePluginIsRejected() throws IOException {
        PluginManager fresh = new PluginManager(new DefaultCapabilityRegistry(), new InProcessEventBus(Runnable::run));
        String id = fresh.loadPlugin(jar("v1-again.jar", ReloadFixtures.V1.class));
        Path v2 = jar("v2.jar", ReloadFixtures.V2.class);

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> fresh.reloadPlugin(id, v2));

        assertTrue(error.getMessage().contains("LOADED"), error.getMessage());
    }

    @Test
    void deactivateWaitsForInFlightInvocationBeforeShutdown() throws Exception {
        CountDownLatch gate = new CountDownLatch(1);
        ReloadFixtures.v1Entered = new CountDownLatch(1);
        ReloadFixtures.v1Gate = gate;
        CompletableFuture<CapabilityResult> inFlight = CompletableFuture.supplyAsync(() -> invoke(ReloadFixtures.ECHO));
        assertTrue(ReloadFixtures.v1Entered.await(5, TimeUnit.SECONDS));

        manager.deactivatePlugin(ReloadFixtures.PLUGIN_ID);

        assertTrue(registry.find(ReloadFixtures.ECHO).isEmpty());
        assertEquals(0, ReloadFixtures.shutdowns("1.0.0"));
        gate.countDown();
        assertEquals("1.0.0", inFlight.get(5, TimeUnit.SECONDS).output());
        assertEquals(1, ReloadFixtures.shutdowns("1.0.0"));
    }

    private void assertStillOnVersionOne() {
        assertEquals(PluginLifecycle.ACTIVE, manager.getLifecycle(ReloadFixtures.PLUGIN_ID));
        assertEquals("1.0.0", manager.version(ReloadFixtures.PLUGIN_ID).orElseThrow());
        assertEquals("1.0.0", invoke(ReloadFixtures.ECHO).output());
        assertTrue(capabilityIds().containsAll(Set.of(ReloadFixtures.ECHO, ReloadFixtures.LEGACY)));
        assertFalse(capabilityIds().contains(ReloadFixtures.ADDED));
        assertEquals(Set.of("planner-1.0.0"), plannerIds());
        assertEquals(0, ReloadFixtures.shutdowns("1.0.0"));
    }

    private Path jar(String name, Class<? extends me.virul.nexora.spi.NexoraPlugin> pluginClass) throws IOException {
        return ReloadFixtures.jarFor(dir, name, pluginClass);
    }

    private CapabilityResult invoke(String capabilityId) {
        return registry.find(capabilityId).orElseThrow().execute(request(capabilityId));
    }

    private static CapabilityRequest request(String capabilityId) {
        return new CapabilityRequest(
                capabilityId, "step-1", "idem-1", Map.of(), TraceContext.root(), null, "exec-1", 0);
    }

    private Set<String> capabilityIds() {
        return registry.listAll().stream().map(CapabilityDescriptor::id).collect(Collectors.toSet());
    }

    private Set<String> plannerIds() {
        return manager.registeredPlanners().stream()
                .map(p -> p.descriptor().id())
                .collect(Collectors.toSet());
    }
}
