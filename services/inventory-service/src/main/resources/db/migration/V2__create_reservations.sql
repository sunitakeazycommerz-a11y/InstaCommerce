CREATE TYPE reservation_status AS ENUM ('PENDING', 'CONFIRMED', 'CANCELLED', 'EXPIRED');

CREATE TABLE reservations (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    idempotency_key VARCHAR(64)        NOT NULL,
    store_id        VARCHAR(50)        NOT NULL,
    status          reservation_status NOT NULL DEFAULT 'PENDING',
    expires_at      TIMESTAMPTZ        NOT NULL,
    created_at      TIMESTAMPTZ        NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ        NOT NULL DEFAULT now(),
    CONSTRAINT uq_reservation_idempotency UNIQUE (idempotency_key)
);

CREATE INDEX idx_reservations_status ON reservations (status) WHERE status = 'PENDING';
CREATE INDEX idx_reservations_expiry ON reservations (expires_at) WHERE status = 'PENDING';
