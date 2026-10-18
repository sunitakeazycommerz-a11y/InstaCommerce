-- V9: Add routing barriers, weather conditions, traffic feeds, and breach compensation
-- Closes #595, #596, #602, #610, #616, #698

-- 1. routing_barriers for bridge closures and road construction (#616)
CREATE TABLE IF NOT EXISTS routing_barriers (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(128) NOT NULL,
    reason VARCHAR(255),
    min_lat NUMERIC(10, 8) NOT NULL,
    min_lng NUMERIC(11, 8) NOT NULL,
    max_lat NUMERIC(10, 8) NOT NULL,
    max_lng NUMERIC(11, 8) NOT NULL,
    detour_penalty_km NUMERIC(6, 2) NOT NULL DEFAULT 3.5,
    detour_penalty_minutes INT NOT NULL DEFAULT 12,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    expires_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_routing_barriers_active ON routing_barriers(is_active);

-- 2. weather_conditions for monsoon and rainy weather multipliers (#698)
CREATE TABLE IF NOT EXISTS weather_conditions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    store_id UUID NOT NULL UNIQUE,
    condition_type VARCHAR(64) NOT NULL DEFAULT 'CLEAR',
    speed_penalty_multiplier NUMERIC(4, 2) NOT NULL DEFAULT 1.0,
    monsoon_delay_banner BOOLEAN NOT NULL DEFAULT FALSE,
    notes VARCHAR(255),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- 3. traffic_congestion_feeds for OSRM real-time municipal traffic speed updates (#602)
CREATE TABLE IF NOT EXISTS traffic_congestion_feeds (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    zone_id VARCHAR(64) NOT NULL UNIQUE,
    congestion_factor NUMERIC(4, 2) NOT NULL DEFAULT 1.0,
    source VARCHAR(64) NOT NULL DEFAULT 'MUNICIPAL_FEED',
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- 4. Alter deliveries for breach compensation, vehicle type, and weather delay (#596, #610, #698)
ALTER TABLE deliveries
    ADD COLUMN IF NOT EXISTS is_breach_risk BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS compensation_triggered BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS compensation_amount_cents INT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS vehicle_type VARCHAR(32) DEFAULT 'MOTORCYCLE',
    ADD COLUMN IF NOT EXISTS weather_delay_active BOOLEAN NOT NULL DEFAULT FALSE;
