-- Add packaging, temperature, and tax attributes to products
-- Defaults ensure backward-compatibility with existing products
ALTER TABLE products
    ADD COLUMN IF NOT EXISTS is_perishable BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN IF NOT EXISTS temperature_zone VARCHAR(20) NOT NULL DEFAULT 'AMBIENT',
    ADD COLUMN IF NOT EXISTS vat_rate_bps INT NOT NULL DEFAULT 0;

CREATE INDEX idx_products_temperature_zone ON products (temperature_zone);
