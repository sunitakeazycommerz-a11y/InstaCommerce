CREATE TYPE order_status AS ENUM (
    'PENDING', 'PLACED', 'PACKING', 'PACKED',
    'OUT_FOR_DELIVERY', 'DELIVERED', 'CANCELLED', 'FAILED'
);

CREATE TABLE orders (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id             UUID            NOT NULL,
    store_id            VARCHAR(50)     NOT NULL,
    status              order_status    NOT NULL DEFAULT 'PENDING',
    subtotal_cents      BIGINT          NOT NULL,
    discount_cents      BIGINT          NOT NULL DEFAULT 0,
    total_cents         BIGINT          NOT NULL,
    currency            VARCHAR(3)      NOT NULL DEFAULT 'INR',
    coupon_code         VARCHAR(30),
    reservation_id      UUID,
    payment_id          UUID,
    idempotency_key     VARCHAR(64)     NOT NULL,
    cancellation_reason TEXT,
    created_at          TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ     NOT NULL DEFAULT now(),
    version             BIGINT          NOT NULL DEFAULT 0,
    CONSTRAINT uq_order_idempotency UNIQUE (idempotency_key)
);

CREATE INDEX idx_orders_user    ON orders (user_id);
CREATE INDEX idx_orders_status  ON orders (status);
CREATE INDEX idx_orders_created ON orders (created_at DESC);
