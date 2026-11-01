package com.nexora.samples.tenancy;

import static org.assertj.core.api.Assertions.assertThat;

import com.nexora.core.execution.ExecutionStatus;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Every claim the walkthrough prints is checked here, so the demo cannot drift from the behaviour. */
class MultiTenantDemoAppTest {

    @Test
    void walkthroughShowsEachTenantIsolatedFromTheOther() throws Exception {
        ByteArrayOutputStream printed = new ByteArrayOutputStream();

        MultiTenantDemoApp.Outcome outcome =
                MultiTenantDemoApp.walkthrough(new PrintStream(printed, true, StandardCharsets.UTF_8));

        assertThat(outcome.invoiceCurrencyByTenant()).isEqualTo(Map.of("acme", "EUR", "globex", "USD"));
        assertThat(outcome.acmeLoyalty()).isEqualTo(ExecutionStatus.COMPLETED);
        assertThat(outcome.globexLoyalty()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(outcome.acmeHistory()).isEqualTo(2);
        assertThat(outcome.globexHistory()).isEqualTo(2);
        assertThat(outcome.boundEngineRefusedOtherTenant()).isTrue();
        assertThat(outcome.boundEngineCouldNotReplayOtherTenant()).isTrue();
        assertThat(outcome.overdueScheduleFirings()).isEqualTo(1);
        assertThat(outcome.completedMetrics())
                .contains("nexora_plan_completed_total{tenant=\"acme\",} 2.0")
                .contains("nexora_plan_completed_total{tenant=\"globex\",} 1.0");
        assertThat(printed.toString(StandardCharsets.UTF_8)).contains("fired 1 time(s)");
    }
}
