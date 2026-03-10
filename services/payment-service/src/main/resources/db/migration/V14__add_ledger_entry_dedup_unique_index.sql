-- flyway:executeInTransaction=false
-- Partial unique index: prevents duplicate ledger entries for the same payment,
-- reference, and entry type. NULL reference_id rows are excluded so that
-- entries without a reference remain unaffected.
CREATE UNIQUE INDEX CONCURRENTLY idx_ledger_entries_dedup
    ON ledger_entries (payment_id, reference_type, reference_id, entry_type)
    WHERE reference_id IS NOT NULL;
