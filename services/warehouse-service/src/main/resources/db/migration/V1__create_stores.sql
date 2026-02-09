CREATE TABLE stores (
    id                       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name                     VARCHAR(255)    NOT NULL,
    address                  TEXT            NOT NULL,
    city                     VARCHAR(100)    NOT NULL,
    state                    VARCHAR(100)    NOT NULL,
    pincode                  VARCHAR(10)     NOT NULL,
    latitude                 DECIMAL(10, 8)  NOT NULL,
    longitude                DECIMAL(11, 8)  NOT NULL,
    status                   VARCHAR(30)     NOT NULL DEFAULT 'ACTIVE',
    capacity_orders_per_hour INT             NOT NULL DEFAULT 100,
    created_at               TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at               TIMESTAMPTZ     NOT NULL DEFAULT now()
);

CREATE INDEX idx_stores_status ON stores (status);
CREATE INDEX idx_stores_city ON stores (city);
CREATE INDEX idx_stores_pincode ON stores (pincode);
CREATE INDEX idx_stores_lat_lng ON stores (latitude, longitude);
