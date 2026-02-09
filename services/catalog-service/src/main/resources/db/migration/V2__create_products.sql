CREATE TABLE products (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    sku              VARCHAR(50)   NOT NULL,
    name             VARCHAR(255)  NOT NULL,
    slug             VARCHAR(280)  NOT NULL,
    description      TEXT,
    category_id      UUID          REFERENCES categories(id),
    brand            VARCHAR(100),
    base_price_cents BIGINT        NOT NULL,
    currency         VARCHAR(3)    NOT NULL DEFAULT 'INR',
    unit             VARCHAR(20)   NOT NULL DEFAULT 'piece',
    unit_value       DECIMAL(10,3) NOT NULL DEFAULT 1.0,
    weight_grams     INT,
    is_active        BOOLEAN       NOT NULL DEFAULT true,
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    version          BIGINT        NOT NULL DEFAULT 0,
    search_vector    TSVECTOR,
    CONSTRAINT uq_product_sku  UNIQUE (sku),
    CONSTRAINT uq_product_slug UNIQUE (slug),
    CONSTRAINT chk_price_positive CHECK (base_price_cents > 0)
);

CREATE INDEX idx_products_category  ON products (category_id);
CREATE INDEX idx_products_active    ON products (is_active) WHERE is_active = true;
CREATE INDEX idx_products_search    ON products USING GIN (search_vector);

CREATE OR REPLACE FUNCTION products_search_trigger() RETURNS TRIGGER AS $$
BEGIN
    NEW.search_vector :=
        setweight(to_tsvector('english', COALESCE(NEW.name, '')), 'A') ||
        setweight(to_tsvector('english', COALESCE(NEW.brand, '')), 'B') ||
        setweight(to_tsvector('english', COALESCE(NEW.description, '')), 'C');
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_products_search
    BEFORE INSERT OR UPDATE ON products
    FOR EACH ROW EXECUTE FUNCTION products_search_trigger();
