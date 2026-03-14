-- Wave 29 Track F: Align store_id type with warehouse-service and rider-fleet-service.
-- Converts store_id from VARCHAR(50) to UUID across all inventory tables.
-- Existing values must be valid UUID strings; the USING clause handles the cast.

-- 1. stock_items
ALTER TABLE stock_items ALTER COLUMN store_id TYPE UUID USING store_id::uuid;

-- 2. reservations
ALTER TABLE reservations ALTER COLUMN store_id TYPE UUID USING store_id::uuid;

-- 3. stock_adjustment_log
ALTER TABLE stock_adjustment_log ALTER COLUMN store_id TYPE UUID USING store_id::uuid;
