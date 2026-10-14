package com.nexora.cli;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nexora.api.auth.ApiAuth;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/** The CLI's logback.xml must keep the auth audit trail; the root logger alone is WARN and would drop it. */
class AuditLoggingConfigTest {

    @Test
    void authAuditLogsAtInfo() {
        assertTrue(LoggerFactory.getLogger(ApiAuth.class).isInfoEnabled());
    }
}
