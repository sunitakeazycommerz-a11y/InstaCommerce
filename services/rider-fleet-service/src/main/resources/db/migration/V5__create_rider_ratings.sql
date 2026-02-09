CREATE TABLE rider_ratings (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    rider_id   UUID        NOT NULL REFERENCES riders(id),
    order_id   UUID        NOT NULL UNIQUE,
    rating     INT         NOT NULL CHECK (rating >= 1 AND rating <= 5),
    comment    TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_rider_ratings_rider ON rider_ratings (rider_id);
