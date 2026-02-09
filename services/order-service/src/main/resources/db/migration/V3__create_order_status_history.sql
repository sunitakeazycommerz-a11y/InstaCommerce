CREATE TABLE order_status_history (
    id          BIGSERIAL   PRIMARY KEY,
    order_id    UUID        NOT NULL REFERENCES orders(id) ON DELETE CASCADE,
    from_status order_status,
    to_status   order_status NOT NULL,
    changed_by  VARCHAR(100),
    note        TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_osh_order ON order_status_history (order_id);
