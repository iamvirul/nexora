package com.nexora.persistence;

import com.nexora.persistence.jdbc.JdbcExecutionStore;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcExecutionStoreHealthTest {

    @Test
    void isHealthyReturnsTrueWhileConnectionIsOpen() {
        try (JdbcExecutionStore store = JdbcExecutionStore.h2InMemory()) {
            assertTrue(store.isHealthy());
        }
    }

    @Test
    void isHealthyReturnsFalseOnceConnectionIsClosed() {
        JdbcExecutionStore store = JdbcExecutionStore.h2InMemory();
        store.close();

        assertFalse(store.isHealthy());
    }
}
