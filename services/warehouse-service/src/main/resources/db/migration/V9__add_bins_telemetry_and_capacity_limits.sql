-- V9: Bin location management, cold-chain staging telemetry, and store concurrent capacity limits

-- 1. Store concurrent capacity limits (#592)
ALTER TABLE stores ADD COLUMN max_concurrent_orders INT NOT NULL DEFAULT 100;
ALTER TABLE stores ADD COLUMN current_pending_orders INT NOT NULL DEFAULT 0;

-- 2. Bin location management (#580)
CREATE TABLE warehouse_bins (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    store_id             UUID         NOT NULL REFERENCES stores(id) ON DELETE CASCADE,
    aisle                VARCHAR(20)  NOT NULL,
    rack                 VARCHAR(20)  NOT NULL,
    shelf                VARCHAR(20)  NOT NULL,
    bin_number           VARCHAR(20)  NOT NULL,
    sku                  VARCHAR(100),
    max_volume_cm3       INT          NOT NULL DEFAULT 50000,
    current_volume_cm3   INT          NOT NULL DEFAULT 0,
    max_weight_grams     INT          NOT NULL DEFAULT 25000,
    current_weight_grams INT          NOT NULL DEFAULT 0,
    occupied             BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_warehouse_bins_location UNIQUE (store_id, aisle, rack, shelf, bin_number)
);

CREATE INDEX idx_warehouse_bins_store ON warehouse_bins (store_id);
CREATE INDEX idx_warehouse_bins_store_sku ON warehouse_bins (store_id, sku);

-- 3. Cold chain staging telemetry (#585)
CREATE TABLE staging_temperature_telemetry (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    store_id             UUID          NOT NULL REFERENCES stores(id) ON DELETE CASCADE,
    sensor_id            VARCHAR(50)   NOT NULL,
    bay_id               VARCHAR(50)   NOT NULL,
    storage_type         VARCHAR(20)   NOT NULL,
    temperature_celsius  DECIMAL(5, 2) NOT NULL,
    battery_pct          INT,
    recorded_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    breached             BOOLEAN       NOT NULL DEFAULT FALSE,
    alert_triggered      BOOLEAN       NOT NULL DEFAULT FALSE
);

CREATE INDEX idx_staging_telemetry_store_bay ON staging_temperature_telemetry (store_id, bay_id, recorded_at);
