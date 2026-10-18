-- V11: Add dark store aisle routing, multi-order batch picking, cryptographic handoff and proof of delivery
ALTER TABLE pick_items ADD COLUMN aisle VARCHAR(20);
ALTER TABLE pick_items ADD COLUMN shelf INT;
ALTER TABLE pick_items ADD COLUMN bin VARCHAR(20);
ALTER TABLE pick_items ADD COLUMN pick_sequence INT;
ALTER TABLE pick_items ADD COLUMN barcode VARCHAR(100);
ALTER TABLE pick_items ADD COLUMN tare_weight_grams INT;

ALTER TABLE pick_tasks ADD COLUMN batch_id UUID;
ALTER TABLE pick_tasks ADD COLUMN tote_barcode VARCHAR(50);
ALTER TABLE pick_tasks ADD COLUMN tote_color VARCHAR(30);

ALTER TABLE deliveries ADD COLUMN dispatch_otp VARCHAR(10);
ALTER TABLE deliveries ADD COLUMN pod_photo_url VARCHAR(500);
ALTER TABLE deliveries ADD COLUMN pod_photo_format VARCHAR(20);
ALTER TABLE deliveries ADD COLUMN pod_photo_size_bytes INT;
ALTER TABLE deliveries ADD COLUMN pod_masked BOOLEAN DEFAULT false;

CREATE TABLE pick_batches (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    store_id     VARCHAR(50) NOT NULL,
    picker_id    UUID,
    status       VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    cart_id      VARCHAR(50) NOT NULL,
    total_orders INT NOT NULL,
    total_items  INT NOT NULL,
    started_at   TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_pick_batches_store ON pick_batches (store_id, status);
CREATE INDEX idx_pick_items_location ON pick_items (aisle, shelf, bin);
CREATE INDEX idx_pick_tasks_batch ON pick_tasks (batch_id);
