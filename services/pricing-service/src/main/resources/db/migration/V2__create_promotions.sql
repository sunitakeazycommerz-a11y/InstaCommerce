CREATE TABLE promotions (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name              VARCHAR(255)  NOT NULL,
    description       TEXT,
    discount_type     VARCHAR(20)   NOT NULL,
    discount_value    DECIMAL(12,2) NOT NULL,
    min_order_cents   BIGINT        NOT NULL DEFAULT 0,
    max_discount_cents BIGINT,
    start_at          TIMESTAMPTZ   NOT NULL,
    end_at            TIMESTAMPTZ   NOT NULL,
    active            BOOLEAN       NOT NULL DEFAULT true,
    max_uses          INTEGER,
    current_uses      INTEGER       NOT NULL DEFAULT 0,
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE INDEX idx_promotions_active_dates ON promotions (active, start_at, end_at)
    WHERE active = true;
