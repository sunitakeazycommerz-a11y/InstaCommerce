ALTER TABLE rider_assignments
    ADD COLUMN IF NOT EXISTS distance_km        DOUBLE PRECISION,
    ADD COLUMN IF NOT EXISTS order_weight_grams INT;
