package com.meridian.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaymentIntentMigrationsTest {
    @Test
    void appliesOnlyToPostgresUrls() {
        assertTrue(PaymentIntentMigrations.appliesTo("jdbc:postgresql://localhost:5432/meridian"));
        assertFalse(PaymentIntentMigrations.appliesTo("jdbc:h2:mem:ledger"));
        assertFalse(PaymentIntentMigrations.appliesTo(null));
    }

    @Test
    void leavesTheH2RehearsalDatabaseUntouched() {
        assertDoesNotThrow(() -> PaymentIntentMigrations.migrate(null, "jdbc:h2:mem:ledger;DB_CLOSE_DELAY=-1"));
        assertDoesNotThrow(() -> PaymentIntentMigrations.migrate(null, null));
    }
}
