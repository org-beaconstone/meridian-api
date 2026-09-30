package com.meridian.domain;

import java.util.UUID;

/**
 * Values required to persist an intent before any provider call.
 * Status is always {@link PaymentIntentStatus#PENDING_SCA} on insert.
 * A null currency is stored as GBP.
 */
public record NewPaymentIntent(
        UUID accountId,
        String idempotencyKey,
        String recipientId,
        long amountMinor,
        String currency,
        String corridor,
        String providerId,
        String scaReference) {
}
