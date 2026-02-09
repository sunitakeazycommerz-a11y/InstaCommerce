CREATE TABLE wallets (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID         NOT NULL UNIQUE,
    balance_cents BIGINT     NOT NULL DEFAULT 0 CHECK (balance_cents >= 0),
    currency    VARCHAR(3)   NOT NULL DEFAULT 'INR',
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version     BIGINT       NOT NULL DEFAULT 0
);

CREATE INDEX idx_wallets_user_id ON wallets (user_id);
