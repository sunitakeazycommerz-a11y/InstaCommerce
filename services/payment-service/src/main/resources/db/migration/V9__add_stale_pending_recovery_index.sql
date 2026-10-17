-- Partial index to efficiently find payments stuck in a pending state.
-- Used by the stale-pending recovery job which queries by status + updated_at.
CREATE INDEX IF NOT EXISTS idx_payments_pending_updated
    ON payments (status, updated_at)
    WHERE status IN ('AUTHORIZE_PENDING', 'CAPTURE_PENDING', 'VOID_PENDING');
