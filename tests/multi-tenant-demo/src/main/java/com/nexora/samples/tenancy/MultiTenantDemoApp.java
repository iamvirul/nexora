package com.nexora.samples.tenancy;

import com.nexora.api.ExecutionNotFoundException;
import com.nexora.api.NexoraEngine;
import com.nexora.api.observability.NexoraObservability;
import com.nexora.core.capability.CapabilityResult;
import com.nexora.core.execution.ExecutionResult;
import com.nexora.core.execution.ExecutionStatus;
import com.nexora.core.intent.Intent;
import com.nexora.persistence.ExecutionFilter;
import com.nexora.persistence.ExecutionRecord;
import com.nexora.persistence.ExecutionStore;
import com.nexora.persistence.MissedFirePolicy;
import com.nexora.persistence.ScheduleRecord;
import com.nexora.persistence.jdbc.HikariCPDataSourceFactory;
import com.nexora.persistence.jdbc.JdbcExecutionStore;
import com.nexora.persistence.jdbc.PoolConfig;
import com.nexora.planner.model.StepDefinition;
import com.nexora.spi.Capability;
import com.nexora.spi.CapabilityDescriptor;
import com.nexora.spi.CapabilityProvider;
import com.nexora.spi.NexoraPlugin;
import com.nexora.spi.PluginContext;
import com.nexora.spi.PluginDescriptor;
import com.zaxxer.hikari.HikariDataSource;
import java.io.PrintStream;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Multi-tenancy with the Nexora library, walked through step by step on one shared store:
 *
 * <ol>
 *   <li>one engine serving two tenants, with a capability that reads the tenant to pick per-tenant
 *       settings;
 *   <li>a capability only one tenant may use;
 *   <li>execution history read within one tenant;
 *   <li>an engine bound to one tenant, which refuses and cannot see another tenant's work;
 *   <li>an overdue schedule that fires exactly once, although two engines load it;
 *   <li>execution metrics labelled with the tenant.
 * </ol>
 *
 * <p>Every engine gets its own {@link JdbcExecutionStore} over one caller-owned connection pool. An
 * engine closes its store on shutdown, so engines must not share one store instance; a store built
 * on a caller's {@code DataSource} leaves that pool open for the others.
 *
 * <p>The observe server side (users bound to tenants, {@code X-Tenant-ID}) is in the login demo.
 */
public final class MultiTenantDemoApp {

    static final String ACME = "acme";
    static final String GLOBEX = "globex";

    /** Per-tenant settings a capability looks up by the tenant of the execution it serves. */
    static final Map<String, String> INVOICE_CURRENCY = Map.of(ACME, "EUR", GLOBEX, "USD");

    private static final long WAIT_SECONDS = 5;
    // Yearly, so its next legitimate firing never lands inside the walkthrough's counting window.
    private static final String YEARLY_CRON = "0 0 1 1 *";
    // Long enough for a wrongly duplicated catch-up firing to have run and been counted.
    private static final long SETTLE_MILLIS = 500;

    /** What the walkthrough observed, so the test can check every claim it prints. */
    record Outcome(
            Map<String, String> invoiceCurrencyByTenant,
            ExecutionStatus acmeLoyalty,
            ExecutionStatus globexLoyalty,
            int acmeHistory,
            int globexHistory,
            boolean boundEngineRefusedOtherTenant,
            boolean boundEngineCouldNotReplayOtherTenant,
            int overdueScheduleFirings,
            List<String> completedMetrics) {}

    private final Map<String, String> invoiceCurrency = new ConcurrentHashMap<>();
    private final AtomicInteger reportFirings = new AtomicInteger();

    private MultiTenantDemoApp() {}

    public static void main(String[] args) throws Exception {
        new MultiTenantDemoApp().run(System.out);
    }

    static Outcome walkthrough(PrintStream out) throws Exception {
        return new MultiTenantDemoApp().run(out);
    }

    private Outcome run(PrintStream out) throws Exception {
        out.println("Nexora multi-tenancy walkthrough");
        out.println("================================");
        try (HikariDataSource pool = HikariCPDataSourceFactory.create(
                        "jdbc:h2:mem:tenancy-demo-" + System.nanoTime(), PoolConfig.defaults());
                JdbcExecutionStore store = new JdbcExecutionStore(pool);
                NexoraEngine shared = builder(pool).build();
                NexoraObservability metrics = NexoraObservability.attach(shared)) {

            out.println();
            out.println("1. One engine, two tenants. The invoice capability reads request.tenantId():");
            for (String tenant : List.of(ACME, GLOBEX)) {
                run(shared, intent("invoice", tenant));
                out.printf("   %-7s invoices in %s%n", tenant, invoiceCurrency.get(tenant));
            }

            out.println();
            out.println("2. loyalty-points is registered for acme only (CapabilityDescriptor.withTenantIds):");
            ExecutionStatus acmeLoyalty = run(shared, intent("loyalty", ACME)).status();
            ExecutionResult globexLoyalty = run(shared, intent("loyalty", GLOBEX));
            out.println("   acme    " + acmeLoyalty);
            out.println("   globex  " + globexLoyalty.status() + " (CAPABILITY_NOT_FOUND, as for an unknown id)");

            out.println();
            out.println("3. History within one tenant (ExecutionFilter.forTenant):");
            int acmeHistory = history(store, ACME).size();
            int globexHistory = history(store, GLOBEX).size();
            out.println("   acme    " + acmeHistory + " executions");
            out.println("   globex  " + globexHistory + " executions");

            out.println();
            out.println("4. An engine bound to acme (Builder.withTenantId) on the same database:");
            boolean refused;
            boolean replayHidden;
            try (NexoraEngine acmeOnly = builder(pool).withTenantId(ACME).build()) {
                refused = refuses(acmeOnly, intent("invoice", GLOBEX));
                replayHidden = cannotReplay(acmeOnly, globexLoyalty.executionId());
                out.println("   runs a globex intent?            " + (refused ? "no, refused" : "yes"));
                out.println("   replays globex's failed run?     " + (replayHidden ? "no, not found" : "yes"));
            }

            out.println();
            out.println("5. An overdue globex schedule, loaded by a globex-bound and an unbound engine:");
            store.createSchedule(overdueReport(GLOBEX));
            try (NexoraEngine globexOnly = builder(pool).withTenantId(GLOBEX).build();
                    NexoraEngine unbound = builder(pool).build()) {
                awaitFirings();
            }
            out.println("   fired " + reportFirings.get() + " time(s): each firing is claimed in the store first");

            out.println();
            out.println("6. Execution metrics carry the tenant:");
            List<String> completed = metrics.scrapePrometheus()
                    .lines()
                    .filter(line -> line.startsWith("nexora_plan_completed_total{"))
                    .sorted()
                    .toList();
            completed.forEach(line -> out.println("   " + line));

            return new Outcome(
                    new TreeMap<>(invoiceCurrency),
                    acmeLoyalty,
                    globexLoyalty.status(),
                    acmeHistory,
                    globexHistory,
                    refused,
                    replayHidden,
                    reportFirings.get(),
                    completed);
        }
    }

    private static Intent intent(String goal, String tenantId) {
        return new Intent(goal, Map.of()).withTenantId(tenantId);
    }

    private static ExecutionResult run(NexoraEngine engine, Intent intent) throws Exception {
        return engine.execute(intent).get(WAIT_SECONDS, TimeUnit.SECONDS);
    }

    private static List<ExecutionRecord> history(ExecutionStore store, String tenantId) {
        return store.findByFilter(ExecutionFilter.all().forTenant(tenantId));
    }

    private static boolean refuses(NexoraEngine engine, Intent intent) {
        try {
            engine.submit(intent);
            return false;
        } catch (IllegalArgumentException expected) {
            return true;
        }
    }

    private static boolean cannotReplay(NexoraEngine engine, String executionId) {
        try {
            engine.replay(executionId);
            return false;
        } catch (ExecutionNotFoundException expected) {
            return true;
        }
    }

    private void awaitFirings() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        while (reportFirings.get() == 0 && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        Thread.sleep(SETTLE_MILLIS);
    }

    /** Overdue by a year, so every engine that loads it at startup wants to catch it up. */
    private static ScheduleRecord overdueReport(String tenantId) {
        Instant now = Instant.now();
        return new ScheduleRecord(
                "nightly-report",
                YEARLY_CRON,
                "report",
                Map.of(),
                MissedFirePolicy.FIRE_ONCE,
                now.minus(Duration.ofDays(800)),
                null,
                now.minus(Duration.ofDays(365)),
                null,
                true,
                tenantId);
    }

    /** A fresh store per engine over the shared pool; the engine closes the store, never the pool. */
    private NexoraEngine.Builder builder(HikariDataSource pool) {
        return NexoraEngine.builder()
                .withExecutionStore(new JdbcExecutionStore(pool))
                .withPlugin(plugin())
                .withStepDefinition(new StepDefinition("invoice", "invoice", "invoice"::equals))
                .withStepDefinition(new StepDefinition("loyalty", "loyalty-points", "loyalty"::equals))
                .withStepDefinition(new StepDefinition("report", "report", "report"::equals));
    }

    private NexoraPlugin plugin() {
        return new NexoraPlugin() {
            @Override
            public PluginDescriptor descriptor() {
                return new PluginDescriptor("tenancy-demo", "1.0", "Multi-tenancy demo capabilities", List.of(), null);
            }

            @Override
            public void initialize(PluginContext ctx) {}

            @Override
            public void shutdown() {}

            @Override
            public List<CapabilityProvider> capabilityProviders() {
                return List.of(
                        provider(capability("invoice"), request -> {
                            String currency = INVOICE_CURRENCY.get(request.tenantId());
                            invoiceCurrency.put(request.tenantId(), currency);
                            return CapabilityResult.success(Map.of("currency", currency));
                        }),
                        provider(
                                capability("loyalty-points").withTenantIds(Set.of(ACME)),
                                request -> CapabilityResult.success(Map.of("points", 120))),
                        provider(capability("report"), request -> {
                            reportFirings.incrementAndGet();
                            return CapabilityResult.success("report sent");
                        }));
            }
        };
    }

    private static CapabilityDescriptor capability(String id) {
        return new CapabilityDescriptor(id, id, List.of(), List.of(), true, false);
    }

    private static CapabilityProvider provider(CapabilityDescriptor descriptor, Capability capability) {
        return new CapabilityProvider() {
            @Override
            public CapabilityDescriptor descriptor() {
                return descriptor;
            }

            @Override
            public Capability create(PluginContext ctx) {
                return capability;
            }
        };
    }
}
