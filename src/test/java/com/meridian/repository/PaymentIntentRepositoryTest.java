package com.meridian.repository;

import com.meridian.config.PaymentIntentMigrations;
import com.meridian.domain.NewPaymentIntent;
import com.meridian.domain.PaymentIntent;
import com.meridian.domain.PaymentIntentStatus;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaymentIntentRepositoryTest {
    private static EmbeddedPostgres postgres;
    private static JdbcTemplate jdbc;
    private static PaymentIntentRepository repository;

    @BeforeAll
    static void startDatabase() throws Exception {
        postgres = EmbeddedPostgres.builder().start();
        DataSource dataSource = postgres.getPostgresDatabase();
        String url;
        try (var connection = dataSource.getConnection()) {
            url = connection.getMetaData().getURL();
        }
        PaymentIntentMigrations.migrate(dataSource, url);
        PaymentIntentMigrations.migrate(dataSource, url);
        jdbc = new JdbcTemplate(dataSource);
        repository = new PaymentIntentRepository(jdbc);
    }

    @AfterAll
    static void stopDatabase() throws Exception {
        if (postgres != null) {
            postgres.close();
        }
    }

    @BeforeEach
    void clean() {
        jdbc.execute("TRUNCATE payment_intents");
    }

    @Test
    void createsColumnsPrimaryKeyDefaultsAndIndexes() {
        var names = jdbc.queryForList("""
                SELECT column_name
                FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = 'payment_intents'
                ORDER BY ordinal_position
                """, String.class);
        assertEquals(List.of(
                "id", "account_id", "idempotency_key", "recipient_id", "amount_minor", "currency",
                "corridor", "status", "provider_id", "sca_reference", "created_at", "updated_at"), names);

        var idDefault = columnDefault("id");
        var createdDefault = columnDefault("created_at");
        var updatedDefault = columnDefault("updated_at");
        var currencyDefault = columnDefault("currency");
        assertTrue(idDefault.contains("gen_random_uuid()"), idDefault);
        assertTrue(createdDefault.contains("now()"), createdDefault);
        assertTrue(updatedDefault.contains("now()"), updatedDefault);
        assertTrue(currencyDefault.contains("GBP"), currencyDefault);
        assertEquals("uuid", dataType("id"));
        assertEquals("timestamp with time zone", dataType("created_at"));
        assertEquals("NO", nullable("id"));
        assertEquals("YES", nullable("sca_reference"));

        var primaryKey = jdbc.queryForObject("""
                SELECT kcu.column_name
                FROM information_schema.table_constraints tc
                JOIN information_schema.key_column_usage kcu
                  ON tc.constraint_name = kcu.constraint_name
                 AND tc.table_schema = kcu.table_schema
                WHERE tc.table_schema = 'public'
                  AND tc.table_name = 'payment_intents'
                  AND tc.constraint_type = 'PRIMARY KEY'
                """, String.class);
        assertEquals("id", primaryKey);

        Integer uniqueKeys = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM information_schema.table_constraints tc
                JOIN information_schema.key_column_usage kcu
                  ON tc.constraint_name = kcu.constraint_name
                 AND tc.table_schema = kcu.table_schema
                WHERE tc.table_schema = 'public'
                  AND tc.table_name = 'payment_intents'
                  AND tc.constraint_type = 'UNIQUE'
                  AND kcu.column_name = 'idempotency_key'
                """, Integer.class);
        assertEquals(1, uniqueKeys);

        var indexes = jdbc.queryForList("""
                SELECT indexdef
                FROM pg_indexes
                WHERE schemaname = 'public' AND tablename = 'payment_intents'
                """, String.class);
        assertTrue(indexes.stream().anyMatch(def -> def.contains("UNIQUE") && def.contains("(idempotency_key)")), indexes.toString());
        assertTrue(indexes.stream().anyMatch(def -> def.contains("(account_id, created_at DESC)")), indexes.toString());
        assertTrue(indexes.stream().anyMatch(def -> def.contains("(corridor, status)")), indexes.toString());
    }

    @Test
    void databaseDefaultsGenerateIdCurrencyAndTimestamps() {
        UUID accountId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO payment_intents (
                    account_id, idempotency_key, recipient_id, amount_minor, corridor, status, provider_id
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """, accountId, "db-defaults", "birch-bloom", 2599, "UK", "PENDING_SCA", "adyen");
        var saved = repository.findByIdempotencyKey("db-defaults").orElseThrow();
        assertNotNull(saved.id());
        assertEquals(accountId, saved.accountId());
        assertEquals("GBP", saved.currency());
        assertEquals(PaymentIntentStatus.PENDING_SCA, saved.status());
        assertNotNull(saved.createdAt());
        assertNotNull(saved.updatedAt());
        assertFalse(saved.updatedAt().isBefore(saved.createdAt()));
        assertTrue(saved.createdAt().isAfter(Instant.now().minus(2, ChronoUnit.MINUTES)));
    }

    @Test
    void persistsLifecycleForSucceededAndFailedIntents() {
        var pending = repository.insert(draft("life-ok", "adyen", "UK"));
        assertEquals(PaymentIntentStatus.PENDING_SCA, pending.status());
        assertNotNull(pending.id());
        assertEquals("GBP", pending.currency());
        assertEquals(2599L, pending.amountMinor());

        var processing = repository.transition(pending.id(), PaymentIntentStatus.PROCESSING, "sca-sim-ok");
        assertEquals(PaymentIntentStatus.PROCESSING, processing.status());
        assertEquals("sca-sim-ok", processing.scaReference());
        assertFalse(processing.updatedAt().isBefore(pending.updatedAt()));

        var succeeded = repository.transition(processing.id(), PaymentIntentStatus.SUCCEEDED, null);
        assertEquals(PaymentIntentStatus.SUCCEEDED, succeeded.status());
        assertEquals("sca-sim-ok", succeeded.scaReference());
        assertEquals(succeeded, repository.findById(succeeded.id()).orElseThrow());

        var failedPath = repository.insert(draft("life-fail", "worldpay", "US"));
        var failedProcessing = repository.transition(failedPath.id(), PaymentIntentStatus.PROCESSING, "sca-sim-fail");
        var failed = repository.transition(failedProcessing.id(), PaymentIntentStatus.FAILED, null);
        assertEquals(PaymentIntentStatus.FAILED, failed.status());
        assertEquals("worldpay", failed.providerId());

        var scaFailed = repository.transition(
                repository.insert(draft("sca-fail", "adyen", "UK")).id(),
                PaymentIntentStatus.FAILED,
                null);
        assertEquals(PaymentIntentStatus.FAILED, scaFailed.status());

        assertThrows(IllegalStateException.class,
                () -> repository.transition(succeeded.id(), PaymentIntentStatus.FAILED, null));
        assertThrows(IllegalStateException.class,
                () -> repository.transition(pending.id(), PaymentIntentStatus.SUCCEEDED, null));
    }

    @Test
    void rejectsDuplicateIdempotencyKeyAndKeepsTheOriginalRow() {
        var original = repository.insert(draft("same-key", "adyen", "UK"));
        var conflict = assertThrows(IdempotencyKeyConflictException.class,
                () -> repository.insert(draft("same-key", "worldpay", "US")));
        assertEquals("same-key", conflict.getIdempotencyKey());

        var reloaded = repository.findByIdempotencyKey("same-key").orElseThrow();
        assertEquals(original.id(), reloaded.id());
        assertEquals("adyen", reloaded.providerId());
        assertEquals("UK", reloaded.corridor());
        assertEquals(1, countByKey("same-key"));

        jdbc.update("""
                INSERT INTO payment_intents (
                    account_id, idempotency_key, recipient_id, amount_minor, currency, corridor, status, provider_id
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), "raw-key", "birch-bloom", 100, "GBP", "UK", "PENDING_SCA", "adyen");
        assertThrows(DataAccessException.class, () -> jdbc.update("""
                INSERT INTO payment_intents (
                    account_id, idempotency_key, recipient_id, amount_minor, currency, corridor, status, provider_id
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), "raw-key", "london-transit", 200, "GBP", "US", "PROCESSING", "worldpay"));
        assertEquals(1, countByKey("raw-key"));
    }

    @Test
    void rejectsConcurrentInsertsOfTheSameIdempotencyKey() throws Exception {
        var draft = draft("concurrent-key", "adyen", "UK");
        var start = new CountDownLatch(1);
        var done = new CountDownLatch(2);
        var successes = new AtomicInteger();
        var conflicts = new AtomicInteger();
        var pool = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < 2; i++) {
                pool.submit(() -> {
                    try {
                        if (!start.await(10, TimeUnit.SECONDS)) {
                            return;
                        }
                        try {
                            repository.insert(draft);
                            successes.incrementAndGet();
                        } catch (IdempotencyKeyConflictException ex) {
                            conflicts.incrementAndGet();
                        }
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertTrue(done.await(30, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, successes.get());
        assertEquals(1, conflicts.get());
        assertEquals(1, countByKey("concurrent-key"));
    }

    @Test
    void listsAccountHistoryNewestFirstAndFiltersCorridorStatus() {
        UUID accountId = UUID.randomUUID();
        var older = repository.insert(draft(accountId, "older", "adyen", "UK"));
        var newer = repository.insert(draft(accountId, "newer", "worldpay", "US"));
        var otherAccount = repository.insert(draft(UUID.randomUUID(), "other-account", "adyen", "UK"));
        jdbc.update("UPDATE payment_intents SET created_at = ? WHERE id = ?",
                Timestamp.from(Instant.parse("2026-01-01T00:00:00Z")), older.id());
        jdbc.update("UPDATE payment_intents SET created_at = ? WHERE id = ?",
                Timestamp.from(Instant.parse("2026-06-01T00:00:00Z")), newer.id());
        jdbc.update("UPDATE payment_intents SET created_at = ? WHERE id = ?",
                Timestamp.from(Instant.parse("2026-09-01T00:00:00Z")), otherAccount.id());

        assertEquals(List.of(newer.id(), older.id()),
                repository.findByAccountId(accountId).stream().map(PaymentIntent::id).toList());
        assertEquals(List.of(otherAccount.id(), older.id()),
                repository.findByCorridorAndStatus("UK", PaymentIntentStatus.PENDING_SCA).stream().map(PaymentIntent::id).toList());
        assertEquals(List.of(newer.id()),
                repository.findByCorridorAndStatus("US", PaymentIntentStatus.PENDING_SCA).stream().map(PaymentIntent::id).toList());
    }

    @Test
    void databaseRejectsUnknownStatusProviderAndNonPositiveAmount() {
        assertThrows(DataAccessException.class, () -> insertRaw("bad-status", "CAPTURED", "adyen", 100));
        assertThrows(DataAccessException.class, () -> insertRaw("bad-provider", "PENDING_SCA", "other", 100));
        assertThrows(DataAccessException.class, () -> insertRaw("bad-amount", "PENDING_SCA", "adyen", 0));
        assertThrows(IllegalArgumentException.class, () -> repository.insert(draft("bad key", "adyen", "UK")));
        assertThrows(IllegalArgumentException.class, () -> repository.insert(draft("bad-provider-app", "other", "UK")));
    }

    private static String columnDefault(String column) {
        return jdbc.queryForObject("""
                SELECT column_default
                FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = 'payment_intents' AND column_name = ?
                """, String.class, column);
    }

    private static String dataType(String column) {
        return jdbc.queryForObject("""
                SELECT data_type
                FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = 'payment_intents' AND column_name = ?
                """, String.class, column);
    }

    private static String nullable(String column) {
        return jdbc.queryForObject("""
                SELECT is_nullable
                FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = 'payment_intents' AND column_name = ?
                """, String.class, column);
    }

    private static int countByKey(String key) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM payment_intents WHERE idempotency_key = ?", Integer.class, key);
        return count == null ? 0 : count;
    }

    private static void insertRaw(String key, String status, String provider, long amount) {
        jdbc.update("""
                INSERT INTO payment_intents (
                    account_id, idempotency_key, recipient_id, amount_minor, currency, corridor, status, provider_id
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), key, "birch-bloom", amount, "GBP", "UK", status, provider);
    }

    private static NewPaymentIntent draft(String key, String provider, String corridor) {
        return draft(UUID.randomUUID(), key, provider, corridor);
    }

    private static NewPaymentIntent draft(UUID accountId, String key, String provider, String corridor) {
        return new NewPaymentIntent(accountId, key, "birch-bloom", 2599L, null, corridor, provider, null);
    }
}
