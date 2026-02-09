CREATE TABLE store_zones (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    store_id            UUID           NOT NULL REFERENCES stores(id) ON DELETE CASCADE,
    zone_name           VARCHAR(255)   NOT NULL,
    pincode             VARCHAR(10)    NOT NULL,
    delivery_radius_km  DECIMAL(5, 2)  NOT NULL DEFAULT 5.00,
    created_at          TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ    NOT NULL DEFAULT now()
);

ALTER TABLE store_zones ADD CONSTRAINT uq_store_zone_pincode UNIQUE (store_id, pincode);

CREATE INDEX idx_store_zones_pincode ON store_zones (pincode);
CREATE INDEX idx_store_zones_store_id ON store_zones (store_id);
