CREATE TABLE rider_availability (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    rider_id     UUID           NOT NULL UNIQUE REFERENCES riders(id),
    is_available BOOLEAN        NOT NULL DEFAULT false,
    current_lat  DECIMAL(10,8),
    current_lng  DECIMAL(11,8),
    store_id     UUID,
    last_updated TIMESTAMPTZ    NOT NULL DEFAULT now()
);

CREATE INDEX idx_rider_availability_available_store ON rider_availability (is_available, store_id);
