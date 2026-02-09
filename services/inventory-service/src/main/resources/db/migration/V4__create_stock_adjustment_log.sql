CREATE TABLE stock_adjustment_log (
    id           BIGSERIAL PRIMARY KEY,
    product_id   UUID         NOT NULL,
    store_id     VARCHAR(50)  NOT NULL,
    delta        INT          NOT NULL,
    reason       VARCHAR(100) NOT NULL,
    reference_id VARCHAR(255),
    actor_id     UUID,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);
