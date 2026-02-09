CREATE TYPE ledger_entry_type AS ENUM ('DEBIT', 'CREDIT');

CREATE TABLE ledger_entries (
    id              BIGSERIAL PRIMARY KEY,
    payment_id      UUID              NOT NULL REFERENCES payments(id),
    entry_type      ledger_entry_type NOT NULL,
    amount_cents    BIGINT            NOT NULL,
    account         VARCHAR(50)       NOT NULL,
    reference_type  VARCHAR(30)       NOT NULL,
    reference_id    VARCHAR(255),
    description     TEXT,
    created_at      TIMESTAMPTZ       NOT NULL DEFAULT now()
);

CREATE INDEX idx_ledger_payment ON ledger_entries (payment_id);
