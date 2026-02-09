CREATE TABLE reservation_line_items (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    reservation_id  UUID    NOT NULL REFERENCES reservations(id) ON DELETE CASCADE,
    product_id      UUID    NOT NULL,
    quantity        INT     NOT NULL,
    CONSTRAINT chk_qty_positive CHECK (quantity > 0)
);

CREATE INDEX idx_res_line_reservation ON reservation_line_items (reservation_id);
