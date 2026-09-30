-- Synthetic payment-intent store. Idempotency is persisted with the row.
-- sca_reference is a rehearsal token only; this table does not perform SCA.
-- provider_id is limited to the Adyen and Worldpay simulation ports.

CREATE TABLE payment_intents (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id UUID NOT NULL,
    idempotency_key VARCHAR(100) NOT NULL,
    recipient_id VARCHAR(64) NOT NULL,
    amount_minor BIGINT NOT NULL,
    currency VARCHAR(3) NOT NULL DEFAULT 'GBP',
    corridor VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    provider_id VARCHAR(32) NOT NULL,
    sca_reference VARCHAR(128),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_payment_intents_idempotency_key UNIQUE (idempotency_key),
    CONSTRAINT ck_payment_intents_amount_minor CHECK (amount_minor > 0),
    CONSTRAINT ck_payment_intents_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_payment_intents_status CHECK (status IN ('PENDING_SCA', 'PROCESSING', 'SUCCEEDED', 'FAILED')),
    CONSTRAINT ck_payment_intents_provider_id CHECK (provider_id IN ('adyen', 'worldpay'))
);

CREATE INDEX idx_payment_intents_account_id_created_at
    ON payment_intents (account_id, created_at DESC);

CREATE INDEX idx_payment_intents_corridor_status
    ON payment_intents (corridor, status);

CREATE OR REPLACE FUNCTION payment_intents_touch_updated_at()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    NEW.updated_at = clock_timestamp();
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_payment_intents_touch_updated_at
    BEFORE UPDATE ON payment_intents
    FOR EACH ROW
    EXECUTE FUNCTION payment_intents_touch_updated_at();
