CREATE TYPE payment_status AS ENUM ('AUTHORIZED', 'CAPTURED', 'VOIDED', 'PARTIALLY_REFUNDED', 'REFUNDED', 'FAILED');

CREATE TABLE payments (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id        UUID            NOT NULL,
    amount_cents    BIGINT          NOT NULL,
    captured_cents  BIGINT          NOT NULL DEFAULT 0,
    refunded_cents  BIGINT          NOT NULL DEFAULT 0,
    currency        VARCHAR(3)      NOT NULL DEFAULT 'INR',
    status          payment_status  NOT NULL DEFAULT 'AUTHORIZED',
    psp_reference   VARCHAR(255),
    idempotency_key VARCHAR(64)     NOT NULL,
    payment_method  VARCHAR(50),
    metadata        JSONB,
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ     NOT NULL DEFAULT now(),
    version         BIGINT          NOT NULL DEFAULT 0,
    CONSTRAINT uq_payment_idempotency UNIQUE (idempotency_key),
    CONSTRAINT chk_amount_positive CHECK (amount_cents > 0),
    CONSTRAINT chk_refund_le_captured CHECK (refunded_cents <= captured_cents)
);

CREATE INDEX idx_payments_order ON payments (order_id);
CREATE INDEX idx_payments_psp   ON payments (psp_reference);
