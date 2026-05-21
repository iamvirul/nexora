package com.nexora.core.context;

import com.nexora.core.intent.Intent;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ExecutionContextTest {

    @Test
    void shouldStoreAndRetrieveData() {
        ExecutionContext ctx = new ExecutionContext(new Intent("test_goal", Map.of()), TraceContext.root());
        ctx.put("userId", "usr-123");
        ctx.put("amount", 500);

        assertThat(ctx.get("userId", String.class)).isEqualTo(Optional.of("usr-123"));
        assertThat(ctx.get("amount", Integer.class)).isEqualTo(Optional.of(500));
        assertThat(ctx.get("nonexistent", String.class)).isEmpty();
    }

    @Test
    void shouldResolvePaths() {
        Intent intent = new Intent("process_payment", Map.of("currency", "USD"));
        ExecutionContext ctx = new ExecutionContext(intent, TraceContext.root());
        ctx.put("orderId", "ord-999");
        ctx.put("user", Map.of("status", "ACTIVE"));

        assertThat(ctx.resolvePath("intent.goal")).isEqualTo("process_payment");
        assertThat(ctx.resolvePath("intent.context.currency")).isEqualTo("USD");
        assertThat(ctx.resolvePath("context.orderId")).isEqualTo("ord-999");
        assertThat(ctx.resolvePath("user")).isEqualTo(Map.of("status", "ACTIVE"));
    }

    @Test
    void shouldHandleInputOverrides() {
        ExecutionContext ctx = new ExecutionContext(new Intent("test", Map.of()), TraceContext.root());
        
        ctx.putInputOverride("step-1", "flag", true);
        
        assertThat(ctx.getInputOverrides("step-1")).containsEntry("flag", true);
        assertThat(ctx.getInputOverrides("step-2")).isEmpty();
    }
}
