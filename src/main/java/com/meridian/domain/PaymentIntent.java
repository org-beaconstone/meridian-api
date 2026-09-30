package com.meridian.domain;

import java.time.Instant;
import java.util.UUID;

/** Persisted payment intent. Amounts are integer minor units. */
public record PaymentIntent(
        UUID id,
        UUID accountId,
        String idempotencyKey,
        String recipientId,
        long amountMinor,
        String currency,
        String corridor,
        PaymentIntentStatus status,
        String providerId,
        String scaReference,
        Instant createdAt,
        Instant updatedAt) {
}
