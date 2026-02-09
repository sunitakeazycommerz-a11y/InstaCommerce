CREATE TABLE IF NOT EXISTS search_documents (
    id            UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    product_id    UUID         NOT NULL UNIQUE,
    name          VARCHAR(512) NOT NULL,
    description   TEXT,
    brand         VARCHAR(255),
    category      VARCHAR(255),
    price_cents   BIGINT       NOT NULL DEFAULT 0,
    image_url     VARCHAR(2048),
    in_stock      BOOLEAN      NOT NULL DEFAULT TRUE,
    search_vector TSVECTOR,
    created_at    TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at    TIMESTAMP    NOT NULL DEFAULT now()
);

CREATE INDEX idx_search_documents_search_vector ON search_documents USING GIN (search_vector);
CREATE INDEX idx_search_documents_category ON search_documents (category);
CREATE INDEX idx_search_documents_brand ON search_documents (brand);
CREATE INDEX idx_search_documents_price ON search_documents (price_cents);
CREATE INDEX idx_search_documents_in_stock ON search_documents (in_stock);

CREATE OR REPLACE FUNCTION search_documents_update_vector() RETURNS TRIGGER AS $$
BEGIN
    NEW.search_vector :=
        setweight(to_tsvector('english', COALESCE(NEW.name, '')), 'A') ||
        setweight(to_tsvector('english', COALESCE(NEW.brand, '')), 'B') ||
        setweight(to_tsvector('english', COALESCE(NEW.category, '')), 'B') ||
        setweight(to_tsvector('english', COALESCE(NEW.description, '')), 'C');
    NEW.updated_at := now();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_search_documents_vector
    BEFORE INSERT OR UPDATE ON search_documents
    FOR EACH ROW
    EXECUTE FUNCTION search_documents_update_vector();
