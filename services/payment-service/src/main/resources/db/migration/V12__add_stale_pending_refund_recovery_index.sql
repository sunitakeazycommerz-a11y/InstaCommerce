-- Partial index to efficiently find refunds stuck in PENDING state.
-- Used by the stale-pending refund recovery job which queries by status + updated_at.
-- Partial indexing avoids scanning non-pending refunds.
CREATE INDEX IF NOT EXISTS idx_refunds_pending_updated
    ON refunds (updated_at)
    WHERE status = 'PENDING';
