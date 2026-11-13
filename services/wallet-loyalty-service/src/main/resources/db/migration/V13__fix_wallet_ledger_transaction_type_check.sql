ALTER TABLE wallet_ledger_entries DROP CONSTRAINT IF EXISTS wallet_ledger_entries_transaction_type_check;
ALTER TABLE wallet_ledger_entries ADD CONSTRAINT wallet_ledger_entries_transaction_type_check
    CHECK (transaction_type IN ('TOPUP', 'PURCHASE', 'REFUND', 'PROMOTION', 'CASHBACK', 'REFERRAL', 'ADMIN_ADJUSTMENT'));
