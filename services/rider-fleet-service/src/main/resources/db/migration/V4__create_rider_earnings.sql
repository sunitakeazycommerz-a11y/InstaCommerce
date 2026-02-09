CREATE TABLE rider_earnings (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    rider_id          UUID        NOT NULL REFERENCES riders(id),
    order_id          UUID        NOT NULL,
    delivery_fee_cents BIGINT     NOT NULL,
    tip_cents          BIGINT     NOT NULL DEFAULT 0,
    incentive_cents    BIGINT     NOT NULL DEFAULT 0,
    earned_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_rider_earnings_rider_earned ON rider_earnings (rider_id, earned_at);
