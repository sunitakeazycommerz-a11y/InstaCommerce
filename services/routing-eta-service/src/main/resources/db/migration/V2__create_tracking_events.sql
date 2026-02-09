CREATE TABLE delivery_tracking (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    delivery_id UUID           NOT NULL REFERENCES deliveries(id),
    latitude    DECIMAL(10, 8) NOT NULL,
    longitude   DECIMAL(11, 8) NOT NULL,
    speed_kmh   DECIMAL(6, 2),
    heading     DECIMAL(6, 2),
    recorded_at TIMESTAMPTZ    NOT NULL DEFAULT now()
) PARTITION BY RANGE (recorded_at);

-- Create partitions for the next 6 months
CREATE TABLE delivery_tracking_default PARTITION OF delivery_tracking DEFAULT;

CREATE INDEX idx_tracking_delivery_time
    ON delivery_tracking (delivery_id, recorded_at DESC);
