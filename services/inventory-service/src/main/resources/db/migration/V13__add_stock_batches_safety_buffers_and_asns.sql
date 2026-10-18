-- V13: Add stock batches, dynamic safety buffers, and inbound ASNs
-- Closes #579, #583, #593

-- 1. stock_batches for perishable expiration tracking and liquidation (#583)
CREATE TABLE IF NOT EXISTS stock_batches (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    store_id UUID NOT NULL,
    product_id UUID NOT NULL,
    batch_number VARCHAR(64) NOT NULL,
    quantity INT NOT NULL DEFAULT 0,
    expiry_date TIMESTAMPTZ NOT NULL,
    is_discounted BOOLEAN NOT NULL DEFAULT FALSE,
    discount_percent INT NOT NULL DEFAULT 0,
    markdown_triggered_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_store_product_batch UNIQUE (store_id, product_id, batch_number)
);

CREATE INDEX IF NOT EXISTS idx_stock_batches_store_product ON stock_batches(store_id, product_id);
CREATE INDEX IF NOT EXISTS idx_stock_batches_expiry ON stock_batches(expiry_date, is_discounted);

-- 2. sku_safety_stock_buffers for dynamic lead-time buffer calculation (#579)
CREATE TABLE IF NOT EXISTS sku_safety_stock_buffers (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    store_id UUID NOT NULL,
    product_id UUID NOT NULL,
    z_score NUMERIC(4,2) NOT NULL DEFAULT 1.65,
    std_dev_demand NUMERIC(10,2) NOT NULL DEFAULT 0.00,
    lead_time_days INT NOT NULL DEFAULT 1,
    calculated_safety_stock INT NOT NULL DEFAULT 2,
    reorder_threshold INT NOT NULL DEFAULT 5,
    last_reorder_event_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_store_sku_safety UNIQUE (store_id, product_id)
);

CREATE INDEX IF NOT EXISTS idx_sku_safety_buffers_store_sku ON sku_safety_stock_buffers(store_id, product_id);

-- 3. inbound_asns and asn_line_items for automated ASN receiving ingest (#593)
CREATE TABLE IF NOT EXISTS inbound_asns (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    asn_number VARCHAR(64) NOT NULL UNIQUE,
    purchase_order_id VARCHAR(64) NOT NULL,
    vendor_id VARCHAR(64) NOT NULL,
    store_id UUID NOT NULL,
    pallet_barcode VARCHAR(128) NOT NULL UNIQUE,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    received_at TIMESTAMPTZ,
    received_by VARCHAR(64),
    raw_payload TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_inbound_asns_pallet_barcode ON inbound_asns(pallet_barcode);
CREATE INDEX IF NOT EXISTS idx_inbound_asns_store_po ON inbound_asns(store_id, purchase_order_id);

CREATE TABLE IF NOT EXISTS asn_line_items (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    asn_id UUID NOT NULL REFERENCES inbound_asns(id) ON DELETE CASCADE,
    product_id UUID NOT NULL,
    expected_quantity INT NOT NULL,
    received_quantity INT NOT NULL DEFAULT 0,
    batch_number VARCHAR(64),
    expiry_date TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_asn_line_items_asn_id ON asn_line_items(asn_id);
