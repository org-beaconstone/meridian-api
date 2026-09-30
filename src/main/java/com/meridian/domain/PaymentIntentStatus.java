package com.meridian.domain;

/**
 * Persisted rehearsal statuses for a payment intent.
 * Terminal statuses do not transition further and must not be replayed as a new attempt.
 */
public enum PaymentIntentStatus {
    PENDING_SCA,
    PROCESSING,
    SUCCEEDED,
    FAILED;

    public boolean canTransitionTo(PaymentIntentStatus next) {
        if (next == null) {
            return false;
        }
        return switch (this) {
            case PENDING_SCA -> next == PROCESSING || next == FAILED;
            case PROCESSING -> next == SUCCEEDED || next == FAILED;
            case SUCCEEDED, FAILED -> false;
        };
    }
}
