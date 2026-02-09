CREATE TABLE carts (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID            NOT NULL,
    created_at  TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ     NOT NULL DEFAULT now(),
    expires_at  TIMESTAMPTZ     NOT NULL DEFAULT now() + INTERVAL '24 hours',
    version     BIGINT          NOT NULL DEFAULT 0,
    CONSTRAINT uq_cart_user UNIQUE (user_id)
);

CREATE INDEX idx_carts_user       ON carts (user_id);
CREATE INDEX idx_carts_expires_at ON carts (expires_at);
