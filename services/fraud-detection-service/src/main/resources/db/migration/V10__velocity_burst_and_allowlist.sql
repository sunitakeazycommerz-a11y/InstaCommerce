ALTER TABLE velocity_counters
    DROP CONSTRAINT IF EXISTS chk_counter_type;

ALTER TABLE velocity_counters
    ADD CONSTRAINT chk_counter_type
    CHECK (counter_type IN ('ORDERS_5M', 'ORDERS_1H', 'ORDERS_24H', 'AMOUNT_24H', 'FAILED_PAYMENTS_5M', 'FAILED_PAYMENTS_1H'));

ALTER TABLE fraud_rules
    DROP CONSTRAINT IF EXISTS chk_rule_type;

ALTER TABLE fraud_rules
    ADD CONSTRAINT chk_rule_type
    CHECK (rule_type IN ('VELOCITY', 'AMOUNT', 'DEVICE', 'GEO', 'PATTERN', 'COMBINATION'));

CREATE TABLE IF NOT EXISTS allowlisted_entities (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    entity_type     VARCHAR(20)   NOT NULL,
    entity_value    VARCHAR(255)  NOT NULL,
    reason          TEXT,
    allowed_by      VARCHAR(255),
    allowed_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    expires_at      TIMESTAMPTZ,
    active          BOOLEAN       NOT NULL DEFAULT true,
    CONSTRAINT chk_allowlisted_entity_type CHECK (entity_type IN ('USER', 'DEVICE', 'IP', 'PHONE'))
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_allowlisted_entity_active
    ON allowlisted_entities (entity_type, entity_value)
    WHERE active = true;

CREATE INDEX IF NOT EXISTS idx_allowlisted_entity_lookup
    ON allowlisted_entities (entity_type, entity_value, active);
