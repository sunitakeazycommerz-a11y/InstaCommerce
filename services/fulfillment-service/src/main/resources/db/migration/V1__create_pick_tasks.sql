CREATE TYPE pick_task_status AS ENUM ('PENDING', 'IN_PROGRESS', 'COMPLETED', 'CANCELLED');
CREATE TYPE pick_item_status AS ENUM ('PENDING', 'PICKED', 'MISSING', 'SUBSTITUTED');

CREATE TABLE pick_tasks (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id     UUID             NOT NULL,
    user_id      UUID             NOT NULL,
    store_id     VARCHAR(50)      NOT NULL,
    payment_id   UUID,
    picker_id    UUID,
    status       pick_task_status NOT NULL DEFAULT 'PENDING',
    started_at   TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    created_at   TIMESTAMPTZ      NOT NULL DEFAULT now(),
    CONSTRAINT uq_pick_order UNIQUE (order_id)
);

CREATE TABLE pick_items (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    pick_task_id     UUID             NOT NULL REFERENCES pick_tasks(id) ON DELETE CASCADE,
    product_id       UUID             NOT NULL,
    product_name     VARCHAR(255)     NOT NULL,
    quantity         INT              NOT NULL,
    picked_qty       INT              NOT NULL DEFAULT 0,
    unit_price_cents BIGINT           NOT NULL,
    line_total_cents BIGINT           NOT NULL,
    status           pick_item_status NOT NULL DEFAULT 'PENDING',
    substitute_product_id UUID,
    note             TEXT,
    updated_at       TIMESTAMPTZ      NOT NULL DEFAULT now()
);

CREATE INDEX idx_pick_tasks_store  ON pick_tasks (store_id, status);
CREATE INDEX idx_pick_items_task   ON pick_items (pick_task_id);
