CREATE TYPE delivery_status AS ENUM (
    'PENDING', 'RIDER_ASSIGNED', 'PICKED_UP', 'EN_ROUTE',
    'NEAR_DESTINATION', 'DELIVERED', 'FAILED'
);

CREATE TABLE deliveries (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id          UUID            NOT NULL,
    rider_id          UUID,
    store_id          UUID,
    pickup_lat        DECIMAL(10, 8)  NOT NULL,
    pickup_lng        DECIMAL(11, 8)  NOT NULL,
    dropoff_lat       DECIMAL(10, 8)  NOT NULL,
    dropoff_lng       DECIMAL(11, 8)  NOT NULL,
    status            delivery_status NOT NULL DEFAULT 'PENDING',
    estimated_minutes INT,
    actual_minutes    INT,
    distance_km       DECIMAL(8, 3),
    started_at        TIMESTAMPTZ,
    delivered_at      TIMESTAMPTZ,
    created_at        TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ     NOT NULL DEFAULT now(),
    version           BIGINT          NOT NULL DEFAULT 0,
    CONSTRAINT uq_delivery_order UNIQUE (order_id)
);

CREATE INDEX idx_deliveries_status      ON deliveries (status);
CREATE INDEX idx_deliveries_rider       ON deliveries (rider_id, status);
CREATE INDEX idx_deliveries_order       ON deliveries (order_id);
