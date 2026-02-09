CREATE TABLE IF NOT EXISTS trending_queries (
    id               UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    query            VARCHAR(512) NOT NULL UNIQUE,
    hit_count        BIGINT       NOT NULL DEFAULT 1,
    last_searched_at TIMESTAMP    NOT NULL DEFAULT now()
);

CREATE INDEX idx_trending_queries_hit_count ON trending_queries (hit_count DESC);
CREATE INDEX idx_trending_queries_last_searched ON trending_queries (last_searched_at DESC);
