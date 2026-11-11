package me.virul.nexora.spring.boot.autoconfigure;

import java.util.Objects;
import me.virul.nexora.api.NexoraEngine;
import me.virul.nexora.api.NexoraEngine.ReadinessReport;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

/**
 * Reports {@link NexoraEngine#readiness()} as the {@code nexora} Actuator health component: UP
 * only when persistence, its schema migrations, plugins and the executor are all UP. Each check's
 * status, the active plugin ids and the tenant the engine is bound to are added as details.
 *
 * <p>Capability circuit breakers are not part of it, matching the engine's own readiness: one
 * failing downstream must not take every instance out of rotation.
 */
public final class NexoraHealthIndicator implements HealthIndicator {

    static final String DETAIL_ACTIVE_PLUGINS = "activePlugins";
    static final String DETAIL_TENANT_ID = "tenantId";

    private final NexoraEngine engine;

    public NexoraHealthIndicator(NexoraEngine engine) {
        this.engine = Objects.requireNonNull(engine, "engine must not be null");
    }

    @Override
    public Health health() {
        ReadinessReport report = engine.readiness();
        Health.Builder health = report.ready() ? Health.up() : Health.down();
        report.checks().forEach((check, status) -> health.withDetail(check, status.name()));
        health.withDetail(DETAIL_ACTIVE_PLUGINS, engine.activePluginIds());
        engine.tenantScope().ifPresent(tenantId -> health.withDetail(DETAIL_TENANT_ID, tenantId));
        return health.build();
    }
}
