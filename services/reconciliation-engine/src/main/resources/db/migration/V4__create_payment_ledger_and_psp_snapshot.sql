CREATE TABLE IF NOT EXISTS payment_ledger (
    transaction_id VARCHAR(255) PRIMARY KEY,
    payment_id VARCHAR(255) NOT NULL,
    order_id VARCHAR(255),
    psp_reference VARCHAR(255),
    amount_cents BIGINT NOT NULL,
    captured_cents BIGINT NOT NULL DEFAULT 0,
    refunded_cents BIGINT NOT NULL DEFAULT 0,
    currency VARCHAR(3) NOT NULL DEFAULT 'INR',
    status VARCHAR(50) NOT NULL DEFAULT 'UNKNOWN',
    payment_method VARCHAR(50),
    source_lsn BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_payment_ledger_created_at ON payment_ledger(created_at DESC);
CREATE INDEX IF NOT EXISTS idx_payment_ledger_payment_id ON payment_ledger(payment_id);
CREATE INDEX IF NOT EXISTS idx_payment_ledger_order_id ON payment_ledger(order_id);
CREATE INDEX IF NOT EXISTS idx_payment_ledger_psp_reference ON payment_ledger(psp_reference);

CREATE TABLE IF NOT EXISTS psp_export_snapshot (
    id BIGSERIAL PRIMARY KEY,
    transaction_id VARCHAR(255) NOT NULL,
    export_date DATE NOT NULL,
    amount_cents BIGINT NOT NULL,
    currency VARCHAR(3) NOT NULL DEFAULT 'INR',
    status VARCHAR(50) NOT NULL DEFAULT 'SETTLED',
    psp_name VARCHAR(64),
    raw_payload JSONB,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_psp_export_snapshot_txn_date UNIQUE (transaction_id, export_date)
);

CREATE INDEX IF NOT EXISTS idx_psp_export_snapshot_export_date ON psp_export_snapshot(export_date);
