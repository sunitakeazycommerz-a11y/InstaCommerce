-- Optimistic locking support for loyalty_accounts
ALTER TABLE loyalty_accounts ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;
