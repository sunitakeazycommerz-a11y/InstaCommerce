ALTER TYPE order_status ADD VALUE IF NOT EXISTS 'REFUNDED';

ALTER TABLE orders ADD COLUMN IF NOT EXISTS sla_deadline_at TIMESTAMPTZ;
ALTER TABLE orders ADD COLUMN IF NOT EXISTS sla_breached_at TIMESTAMPTZ;
ALTER TABLE orders ADD COLUMN IF NOT EXISTS delivered_at TIMESTAMPTZ;

CREATE INDEX IF NOT EXISTS idx_orders_active_sla
    ON orders (sla_deadline_at)
    WHERE sla_breached_at IS NULL
      AND status IN ('PLACED', 'PACKING', 'PACKED', 'OUT_FOR_DELIVERY');
