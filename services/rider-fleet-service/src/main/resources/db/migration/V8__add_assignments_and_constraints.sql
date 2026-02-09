CREATE TABLE rider_assignments (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id    UUID        NOT NULL,
    rider_id    UUID        NOT NULL REFERENCES riders(id),
    store_id    UUID        NOT NULL,
    assigned_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_rider_assignments_order_id UNIQUE (order_id)
);

CREATE INDEX idx_rider_assignments_rider_id ON rider_assignments (rider_id);

ALTER TABLE rider_earnings
    ADD CONSTRAINT uk_rider_earnings_order_id UNIQUE (order_id);
