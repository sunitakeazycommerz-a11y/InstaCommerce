CREATE TABLE refunds (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    payment_id      UUID        NOT NULL REFERENCES payments(id),
    amount_cents    BIGINT      NOT NULL,
    reason          VARCHAR(255),
    psp_refund_id   VARCHAR(255),
    idempotency_key VARCHAR(64) NOT NULL,
    status          VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_refund_idempotency UNIQUE (idempotency_key),
    CONSTRAINT chk_refund_positive CHECK (amount_cents > 0)
);

CREATE INDEX idx_refunds_payment ON refunds (payment_id);
