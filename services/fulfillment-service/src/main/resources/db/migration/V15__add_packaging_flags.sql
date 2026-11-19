ALTER TABLE pick_items
    ADD COLUMN IF NOT EXISTS cold_chain          BOOLEAN     NOT NULL DEFAULT false,
    ADD COLUMN IF NOT EXISTS fragile             BOOLEAN     NOT NULL DEFAULT false,
    ADD COLUMN IF NOT EXISTS storage_temperature VARCHAR(32);

ALTER TABLE pick_tasks
    ADD COLUMN IF NOT EXISTS cold_chain_required BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN IF NOT EXISTS fragile_required    BOOLEAN NOT NULL DEFAULT false;

ALTER TABLE deliveries
    ADD COLUMN IF NOT EXISTS cold_chain_required BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN IF NOT EXISTS fragile_required    BOOLEAN NOT NULL DEFAULT false;
