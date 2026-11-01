package com.nexora.core.intent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.nexora.core.context.ExecutionContext;
import com.nexora.core.context.TraceContext;
import com.nexora.core.tenant.TenantId;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class IntentTenantTest {

    @Test
    void intentWithoutTenantBelongsToTheDefaultTenant() {
        assertEquals(TenantId.DEFAULT, new Intent("goal", Map.of()).getTenantId());
        assertEquals(TenantId.DEFAULT, new Intent("goal", Map.of(), null, null, null, null).getTenantId());
    }

    @Test
    void withTenantIdKeepsEverythingElse() {
        Intent original =
                new Intent("goal", Map.of("k", "v"), Duration.ofSeconds(5), "https://example.test", List.of());

        Intent acme = original.withTenantId("acme");

        assertEquals("acme", acme.getTenantId());
        assertEquals(original.getGoal(), acme.getGoal());
        assertEquals(original.getContext(), acme.getContext());
        assertEquals(original.getDeadline(), acme.getDeadline());
        assertEquals(original.getWebhookUrl(), acme.getWebhookUrl());
        assertEquals(original.getWebhookEvents(), acme.getWebhookEvents());
    }

    @Test
    void invalidTenantIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new Intent("goal", Map.of(), null, null, null, "Not Valid"));
    }

    @Test
    void executionContextExposesTheIntentsTenant() {
        ExecutionContext ctx =
                new ExecutionContext(new Intent("goal", Map.of()).withTenantId("acme"), TraceContext.root());

        assertEquals("acme", ctx.tenantId());
    }
}
