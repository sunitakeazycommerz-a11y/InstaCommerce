CREATE TABLE price_rules (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    product_id      UUID         NOT NULL,
    base_price_cents BIGINT      NOT NULL,
    effective_from  TIMESTAMPTZ  NOT NULL,
    effective_to    TIMESTAMPTZ,
    rule_type       VARCHAR(30)  NOT NULL DEFAULT 'STANDARD',
    multiplier      DECIMAL(5,2) NOT NULL DEFAULT 1.00,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    active          BOOLEAN      NOT NULL DEFAULT true
);

CREATE INDEX idx_price_rules_product_active ON price_rules (product_id, active)
    WHERE active = true;
