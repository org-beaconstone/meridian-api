package com.meridian.repository;

import com.meridian.domain.NewPaymentIntent;
import com.meridian.domain.PaymentIntent;
import com.meridian.domain.PaymentIntentStatus;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * JDBC store for payment intents. The row, including its idempotency key, is committed
 * before any provider port is invoked. A conflicting key is returned to the caller;
 * this store does not retry the insert or switch provider.
 * The connected rehearsal API stays on the session ledger and integer GBP pence.
 */
@Repository
public class PaymentIntentRepository {
    private static final Set<String> PROVIDERS = Set.of("adyen", "worldpay");
    private static final String COLUMNS = """
            id, account_id, idempotency_key, recipient_id, amount_minor, currency,
            corridor, status, provider_id, sca_reference, created_at, updated_at
            """;
    private static final RowMapper<PaymentIntent> ROW = (rs, rowNum) -> new PaymentIntent(
            rs.getObject("id", UUID.class),
            rs.getObject("account_id", UUID.class),
            rs.getString("idempotency_key"),
            rs.getString("recipient_id"),
            rs.getLong("amount_minor"),
            rs.getString("currency"),
            rs.getString("corridor"),
            PaymentIntentStatus.valueOf(rs.getString("status")),
            rs.getString("provider_id"),
            rs.getString("sca_reference"),
            rs.getObject("created_at", OffsetDateTime.class).toInstant(),
            rs.getObject("updated_at", OffsetDateTime.class).toInstant());

    private final JdbcTemplate jdbc;

    public PaymentIntentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public PaymentIntent insert(NewPaymentIntent draft) {
        validate(draft);
        var currency = draft.currency() == null || draft.currency().isBlank() ? "GBP" : draft.currency();
        try {
            return jdbc.queryForObject("""
                    INSERT INTO payment_intents (
                        account_id, idempotency_key, recipient_id, amount_minor, currency,
                        corridor, status, provider_id, sca_reference
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    RETURNING
                    """ + COLUMNS, ROW,
                    draft.accountId(),
                    draft.idempotencyKey(),
                    draft.recipientId(),
                    draft.amountMinor(),
                    currency,
                    draft.corridor(),
                    PaymentIntentStatus.PENDING_SCA.name(),
                    draft.providerId(),
                    blankToNull(draft.scaReference()));
        } catch (DuplicateKeyException ex) {
            throw conflict(draft.idempotencyKey(), ex);
        } catch (DataIntegrityViolationException ex) {
            if (isUniqueViolation(ex)) {
                throw conflict(draft.idempotencyKey(), ex);
            }
            throw ex;
        }
    }

    public Optional<PaymentIntent> findById(UUID id) {
        var rows = jdbc.query("SELECT " + COLUMNS + " FROM payment_intents WHERE id = ?", ROW, id);
        return rows.stream().findFirst();
    }

    public Optional<PaymentIntent> findByIdempotencyKey(String idempotencyKey) {
        var rows = jdbc.query("SELECT " + COLUMNS + " FROM payment_intents WHERE idempotency_key = ?", ROW, idempotencyKey);
        return rows.stream().findFirst();
    }

    public List<PaymentIntent> findByAccountId(UUID accountId) {
        return jdbc.query("SELECT " + COLUMNS + """
                 FROM payment_intents
                 WHERE account_id = ?
                 ORDER BY created_at DESC, id DESC
                """, ROW, accountId);
    }

    public List<PaymentIntent> findByCorridorAndStatus(String corridor, PaymentIntentStatus status) {
        return jdbc.query("SELECT " + COLUMNS + """
                 FROM payment_intents
                 WHERE corridor = ? AND status = ?
                 ORDER BY created_at DESC, id DESC
                """, ROW, corridor, status.name());
    }

    @Transactional
    public PaymentIntent transition(UUID id, PaymentIntentStatus next, String scaReference) {
        if (id == null || next == null) {
            throw new IllegalArgumentException("Payment intent id and status are required");
        }
        if (scaReference != null) {
            validateScaReference(scaReference);
        }
        var current = findById(id).orElseThrow(() -> new IllegalArgumentException("Unknown payment intent"));
        if (!current.status().canTransitionTo(next)) {
            throw new IllegalStateException("Illegal payment intent status transition");
        }
        var updated = jdbc.query("""
                UPDATE payment_intents
                SET status = ?,
                    sca_reference = COALESCE(?, sca_reference)
                WHERE id = ? AND status = ?
                RETURNING
                """ + COLUMNS, ROW, next.name(), blankToNull(scaReference), id, current.status().name());
        if (updated.isEmpty()) {
            throw new IllegalStateException("Payment intent status changed");
        }
        return updated.getFirst();
    }

    private static void validate(NewPaymentIntent draft) {
        if (draft == null) {
            throw new IllegalArgumentException("Payment intent is required");
        }
        if (draft.accountId() == null) {
            throw new IllegalArgumentException("account_id is required");
        }
        if (draft.idempotencyKey() == null || !draft.idempotencyKey().matches("[A-Za-z0-9_-]{1,100}")) {
            throw new IllegalArgumentException("Invalid idempotency key");
        }
        if (draft.recipientId() == null || !draft.recipientId().matches("[A-Za-z0-9_-]{1,64}")) {
            throw new IllegalArgumentException("Invalid recipient");
        }
        if (draft.amountMinor() < 1) {
            throw new IllegalArgumentException("Amount must be a positive integer of minor units");
        }
        if (draft.currency() != null && !draft.currency().isBlank() && !draft.currency().matches("[A-Z]{3}")) {
            throw new IllegalArgumentException("Invalid currency");
        }
        if (draft.corridor() == null || !draft.corridor().matches("[A-Z0-9_-]{2,32}")) {
            throw new IllegalArgumentException("Invalid corridor");
        }
        if (draft.providerId() == null || !PROVIDERS.contains(draft.providerId())) {
            throw new IllegalArgumentException("Unknown provider");
        }
        if (draft.scaReference() != null) {
            validateScaReference(draft.scaReference());
        }
    }

    private static void validateScaReference(String scaReference) {
        if (!scaReference.matches("[A-Za-z0-9_-]{1,128}") || scaReference.matches("\\d{13,19}")) {
            throw new IllegalArgumentException("Invalid sca_reference");
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static IdempotencyKeyConflictException conflict(String idempotencyKey, RuntimeException cause) {
        return new IdempotencyKeyConflictException(idempotencyKey, cause);
    }

    private static boolean isUniqueViolation(DataIntegrityViolationException ex) {
        var cause = ex.getMostSpecificCause();
        return cause instanceof SQLException sql && "23505".equals(sql.getSQLState());
    }
}
