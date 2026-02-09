CREATE TABLE wallet_ledger_entries (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    wallet_id        UUID         NOT NULL REFERENCES wallets(id),
    debit_account    VARCHAR(255) NOT NULL,
    credit_account   VARCHAR(255) NOT NULL,
    amount_cents     BIGINT       NOT NULL CHECK (amount_cents > 0),
    transaction_type VARCHAR(20)  NOT NULL CHECK (transaction_type IN ('TOPUP', 'PURCHASE', 'REFUND', 'PROMOTION', 'CASHBACK', 'REFERRAL')),
    reference_id     VARCHAR(255) NOT NULL,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_ledger_wallet_created ON wallet_ledger_entries (wallet_id, created_at DESC);
CREATE INDEX idx_ledger_reference ON wallet_ledger_entries (reference_id);
CREATE INDEX idx_ledger_debit_account ON wallet_ledger_entries (debit_account);
CREATE INDEX idx_ledger_credit_account ON wallet_ledger_entries (credit_account);
