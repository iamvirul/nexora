package com.nexora.core.tenant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TenantIdTest {

    @ParameterizedTest
    @ValueSource(strings = {"default", "acme", "team-7", "a_b", "0"})
    void acceptsLowercaseIdsOfLettersDigitsDashesAndUnderscores(String tenantId) {
        assertTrue(TenantId.isValid(tenantId));
        assertEquals(tenantId, TenantId.requireValid(tenantId));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "Acme", "-acme", "_acme", "acme corp", "acme/1", "acme'--", "acme\n"})
    void rejectsIdsThatCouldCollideOrBreakHeadersLogsAndQueries(String tenantId) {
        assertFalse(TenantId.isValid(tenantId));
        assertThrows(IllegalArgumentException.class, () -> TenantId.requireValid(tenantId));
    }

    @Test
    void lengthIsBoundedByTheColumnWidth() {
        assertTrue(TenantId.isValid("a".repeat(TenantId.MAX_LENGTH)));
        assertFalse(TenantId.isValid("a".repeat(TenantId.MAX_LENGTH + 1)));
    }

    @Test
    void nullMeansTheDefaultTenant() {
        assertFalse(TenantId.isValid(null));
        assertEquals(TenantId.DEFAULT, TenantId.orDefault(null));
        assertThrows(IllegalArgumentException.class, () -> TenantId.requireValid(null));
    }
}
