CREATE TABLE store_hours (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    store_id     UUID        NOT NULL REFERENCES stores(id) ON DELETE CASCADE,
    day_of_week  INT         NOT NULL CHECK (day_of_week BETWEEN 0 AND 6),
    opens_at     TIME        NOT NULL,
    closes_at    TIME        NOT NULL,
    is_holiday   BOOLEAN     NOT NULL DEFAULT false,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

ALTER TABLE store_hours ADD CONSTRAINT uq_store_day UNIQUE (store_id, day_of_week);

CREATE INDEX idx_store_hours_store_id ON store_hours (store_id);
