-- flyway:executeInTransaction=false
-- Partial index to efficiently find refunds stuck in PENDING state.
-- Used by the stale-pending refund recovery job which queries by status + updated_at.
-- Concurrent creation avoids holding an exclusive lock on the refunds table.
CREATE INDEX CONCURRENTLY idx_refunds_pending_updated
    ON refunds (updated_at)
    WHERE status = 'PENDING';
