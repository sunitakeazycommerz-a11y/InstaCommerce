ALTER TABLE stores
    ADD COLUMN IF NOT EXISTS surge_mode_enabled         BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN IF NOT EXISTS surge_eta_extension_minutes INT     NOT NULL DEFAULT 15,
    ADD COLUMN IF NOT EXISTS surge_threshold_percent     INT     NOT NULL DEFAULT 90;

CREATE TABLE IF NOT EXISTS store_blackout_windows (
    id         UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    store_id   UUID         NOT NULL REFERENCES stores(id) ON DELETE CASCADE,
    starts_at  TIMESTAMPTZ  NOT NULL,
    ends_at    TIMESTAMPTZ  NOT NULL,
    reason     VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT chk_blackout_window_range CHECK (ends_at > starts_at)
);

CREATE INDEX IF NOT EXISTS idx_blackout_store_window
    ON store_blackout_windows (store_id, starts_at, ends_at);
