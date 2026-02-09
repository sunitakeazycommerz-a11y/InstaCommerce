CREATE TABLE product_images (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    product_id UUID         NOT NULL REFERENCES products(id) ON DELETE CASCADE,
    url        TEXT         NOT NULL,
    alt_text   VARCHAR(255),
    sort_order INT          NOT NULL DEFAULT 0,
    is_primary BOOLEAN      NOT NULL DEFAULT false,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_product_images_product ON product_images (product_id);
