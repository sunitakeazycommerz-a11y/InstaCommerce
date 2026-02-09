CREATE TABLE coupons (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code               VARCHAR(30)  NOT NULL,
    discount_type      VARCHAR(15)  NOT NULL,
    discount_value     BIGINT       NOT NULL,
    min_order_cents    BIGINT       DEFAULT 0,
    max_discount_cents BIGINT,
    valid_from         TIMESTAMPTZ  NOT NULL,
    valid_to           TIMESTAMPTZ  NOT NULL,
    usage_limit        INT,
    per_user_limit     INT          DEFAULT 1,
    first_order_only   BOOLEAN      DEFAULT false,
    is_active          BOOLEAN      NOT NULL DEFAULT true,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_coupon_code UNIQUE (code),
    CONSTRAINT chk_discount_type CHECK (discount_type IN ('PERCENTAGE', 'FLAT_AMOUNT'))
);

CREATE TABLE coupon_usages (
    id        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    coupon_id UUID         NOT NULL REFERENCES coupons(id),
    user_id   UUID         NOT NULL,
    order_id  UUID,
    used_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_coupon_usage UNIQUE (coupon_id, user_id, order_id)
);
