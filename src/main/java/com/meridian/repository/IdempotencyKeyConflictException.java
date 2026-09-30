package com.meridian.repository;

/** The idempotency key is already stored. Callers must reload that row and must not mint a new key. */
public class IdempotencyKeyConflictException extends RuntimeException {
    private final String idempotencyKey;

    public IdempotencyKeyConflictException(String idempotencyKey, Throwable cause) {
        super("Idempotency key already exists", cause);
        this.idempotencyKey = idempotencyKey;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }
}
