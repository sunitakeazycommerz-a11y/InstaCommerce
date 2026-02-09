CREATE TABLE loyalty_accounts (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id          UUID        NOT NULL UNIQUE,
    points_balance   INT         NOT NULL DEFAULT 0,
    lifetime_points  INT         NOT NULL DEFAULT 0,
    tier             VARCHAR(10) NOT NULL DEFAULT 'BRONZE',
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_loyalty_accounts_user_id ON loyalty_accounts (user_id);

CREATE TABLE loyalty_transactions (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id      UUID         NOT NULL REFERENCES loyalty_accounts(id),
    type            VARCHAR(10)  NOT NULL CHECK (type IN ('EARN', 'REDEEM', 'EXPIRE')),
    points          INT          NOT NULL,
    reference_type  VARCHAR(20)  NOT NULL,
    reference_id    VARCHAR(255) NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_loyalty_txn_account_created ON loyalty_transactions (account_id, created_at DESC);
