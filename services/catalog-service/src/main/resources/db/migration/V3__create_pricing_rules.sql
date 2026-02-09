CREATE TABLE pricing_rules (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    product_id           UUID         NOT NULL REFERENCES products(id) ON DELETE CASCADE,
    store_id             VARCHAR(50),
    zone_id              VARCHAR(50),
    override_price_cents BIGINT       NOT NULL,
    valid_from           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    valid_to             TIMESTAMPTZ,
    is_active            BOOLEAN      NOT NULL DEFAULT true,
    priority             INT          NOT NULL DEFAULT 0,
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_pricing_rules_product ON pricing_rules (product_id, is_active);
CREATE INDEX idx_pricing_rules_store   ON pricing_rules (store_id, product_id) WHERE is_active = true;
