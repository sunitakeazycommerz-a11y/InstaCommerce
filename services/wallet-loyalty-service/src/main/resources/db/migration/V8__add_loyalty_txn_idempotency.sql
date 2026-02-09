CREATE UNIQUE INDEX IF NOT EXISTS idx_loyalty_txn_idempotent
    ON loyalty_transactions (reference_type, reference_id);
