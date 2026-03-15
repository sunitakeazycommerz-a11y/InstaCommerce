-- Fix reference_type CHECK constraint to include PROMOTION and ADMIN_ADJUSTMENT
-- which are valid values in the ReferenceType Java enum
ALTER TABLE wallet_transactions DROP CONSTRAINT IF EXISTS wallet_transactions_reference_type_check;
ALTER TABLE wallet_transactions ADD CONSTRAINT wallet_transactions_reference_type_check
    CHECK (reference_type IN ('ORDER', 'REFUND', 'TOPUP', 'CASHBACK', 'REFERRAL', 'PROMOTION', 'ADMIN_ADJUSTMENT'));
