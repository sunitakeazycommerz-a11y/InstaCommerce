CREATE TABLE coupons (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code            VARCHAR(50)  NOT NULL UNIQUE,
    promotion_id    UUID         NOT NULL REFERENCES promotions(id),
    single_use      BOOLEAN      NOT NULL DEFAULT false,
    per_user_limit  INTEGER      NOT NULL DEFAULT 1,
    total_limit     INTEGER,
    total_redeemed  INTEGER      NOT NULL DEFAULT 0,
    active          BOOLEAN      NOT NULL DEFAULT true
);

CREATE TABLE coupon_redemptions (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    coupon_id       UUID         NOT NULL REFERENCES coupons(id),
    user_id         UUID         NOT NULL,
    order_id        UUID         NOT NULL,
    discount_cents  BIGINT       NOT NULL,
    redeemed_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX idx_coupon_single_use ON coupon_redemptions (coupon_id, user_id)
    WHERE coupon_id IN (SELECT id FROM coupons WHERE single_use = true);

CREATE INDEX idx_coupon_redemptions_user ON coupon_redemptions (coupon_id, user_id);
