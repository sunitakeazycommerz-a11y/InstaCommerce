CREATE TABLE riders (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name              VARCHAR(255)   NOT NULL,
    phone             VARCHAR(20)    NOT NULL UNIQUE,
    email             VARCHAR(255),
    vehicle_type      VARCHAR(20)    NOT NULL,
    license_number    VARCHAR(50),
    status            VARCHAR(20)    NOT NULL DEFAULT 'INACTIVE',
    rating_avg        DECIMAL(3,2)   NOT NULL DEFAULT 5.00,
    total_deliveries  INT            NOT NULL DEFAULT 0,
    store_id          UUID,
    created_at        TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ    NOT NULL DEFAULT now()
);

CREATE INDEX idx_riders_status_store ON riders (status, store_id);
CREATE INDEX idx_riders_phone ON riders (phone);
