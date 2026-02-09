CREATE EXTENSION IF NOT EXISTS "pgcrypto";

CREATE TABLE categories (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name        VARCHAR(100)  NOT NULL,
    slug        VARCHAR(120)  NOT NULL,
    parent_id   UUID          REFERENCES categories(id) ON DELETE SET NULL,
    sort_order  INT           NOT NULL DEFAULT 0,
    is_active   BOOLEAN       NOT NULL DEFAULT true,
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT uq_category_slug UNIQUE (slug)
);

CREATE INDEX idx_categories_parent ON categories (parent_id);
CREATE INDEX idx_categories_active ON categories (is_active) WHERE is_active = true;
