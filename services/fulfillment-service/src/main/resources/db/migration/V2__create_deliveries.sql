CREATE TYPE delivery_status AS ENUM ('ASSIGNED', 'PICKED_UP', 'IN_TRANSIT', 'DELIVERED', 'FAILED');

CREATE TABLE riders (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name         VARCHAR(100) NOT NULL,
    phone        VARCHAR(20),
    store_id     VARCHAR(50)  NOT NULL,
    is_available BOOLEAN      NOT NULL DEFAULT true,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE deliveries (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id          UUID            NOT NULL,
    rider_id          UUID            REFERENCES riders(id),
    status            delivery_status NOT NULL DEFAULT 'ASSIGNED',
    estimated_minutes INT,
    dispatched_at     TIMESTAMPTZ,
    delivered_at      TIMESTAMPTZ,
    created_at        TIMESTAMPTZ     NOT NULL DEFAULT now(),
    CONSTRAINT uq_delivery_order UNIQUE (order_id)
);

CREATE INDEX idx_deliveries_rider ON deliveries (rider_id, status);
CREATE INDEX idx_riders_store     ON riders (store_id, is_available);
