ALTER TABLE pick_tasks
    ADD COLUMN IF NOT EXISTS order_weight_grams INTEGER;

ALTER TABLE deliveries
    ADD COLUMN IF NOT EXISTS order_weight_grams INTEGER;
