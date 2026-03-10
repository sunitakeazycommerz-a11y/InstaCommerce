-- flyway:executeInTransaction=false
-- Partial unique index: ensures each PSP refund ID maps to exactly one refund row.
-- NULL values are excluded so that PENDING refunds (which have no psp_refund_id yet) are unaffected.
CREATE UNIQUE INDEX CONCURRENTLY idx_refunds_psp_refund_id
    ON refunds (psp_refund_id)
    WHERE psp_refund_id IS NOT NULL;
