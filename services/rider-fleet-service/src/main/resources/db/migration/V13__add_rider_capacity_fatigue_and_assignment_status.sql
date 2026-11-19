ALTER TABLE riders
    ADD COLUMN IF NOT EXISTS consecutive_deliveries     INT         NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS last_delivery_completed_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS max_capacity_grams         INT;

ALTER TABLE rider_assignments
    ADD COLUMN IF NOT EXISTS status              VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    ADD COLUMN IF NOT EXISTS cancelled_at        TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS cancellation_reason VARCHAR(255);

UPDATE rider_assignments
SET status = 'COMPLETED'
WHERE completed_at IS NOT NULL
  AND status = 'ACTIVE';

ALTER TABLE rider_assignments
    DROP CONSTRAINT IF EXISTS uk_rider_assignments_order_id;

CREATE UNIQUE INDEX IF NOT EXISTS uk_rider_assignments_active_order
    ON rider_assignments (order_id)
    WHERE status = 'ACTIVE';

CREATE INDEX IF NOT EXISTS idx_rider_assignments_status
    ON rider_assignments (order_id, status);
