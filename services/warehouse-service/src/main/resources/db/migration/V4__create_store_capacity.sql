CREATE TABLE store_capacity (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    store_id       UUID        NOT NULL REFERENCES stores(id) ON DELETE CASCADE,
    date           DATE        NOT NULL,
    hour           INT         NOT NULL CHECK (hour BETWEEN 0 AND 23),
    current_orders INT         NOT NULL DEFAULT 0,
    max_orders     INT         NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

ALTER TABLE store_capacity ADD CONSTRAINT uq_store_date_hour UNIQUE (store_id, date, hour);

CREATE INDEX idx_store_capacity_store_date ON store_capacity (store_id, date);
