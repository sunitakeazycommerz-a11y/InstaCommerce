CREATE TABLE wallet_transactions (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    wallet_id           UUID         NOT NULL REFERENCES wallets(id),
    type                VARCHAR(10)  NOT NULL CHECK (type IN ('CREDIT', 'DEBIT')),
    amount_cents        BIGINT       NOT NULL CHECK (amount_cents > 0),
    balance_after_cents BIGINT       NOT NULL,
    reference_type      VARCHAR(20)  NOT NULL CHECK (reference_type IN ('ORDER', 'REFUND', 'TOPUP', 'CASHBACK', 'REFERRAL')),
    reference_id        VARCHAR(255) NOT NULL,
    description         TEXT,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_wallet_txn_wallet_created ON wallet_transactions (wallet_id, created_at DESC);
CREATE UNIQUE INDEX idx_wallet_txn_idempotent ON wallet_transactions (reference_type, reference_id);
