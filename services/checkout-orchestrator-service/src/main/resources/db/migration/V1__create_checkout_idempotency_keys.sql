CREATE TABLE checkout_idempotency_keys (
    id               UUID          NOT NULL DEFAULT gen_random_uuid(),
    idempotency_key  VARCHAR(255)  NOT NULL,
    checkout_response TEXT         NOT NULL,
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    expires_at       TIMESTAMPTZ   NOT NULL,
    PRIMARY KEY (id)
);

CREATE UNIQUE INDEX idx_idempotency_key ON checkout_idempotency_keys (idempotency_key);
