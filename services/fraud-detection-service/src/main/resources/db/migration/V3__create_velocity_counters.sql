CREATE TABLE velocity_counters (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    entity_type     VARCHAR(20)   NOT NULL,
    entity_id       VARCHAR(255)  NOT NULL,
    counter_type    VARCHAR(50)   NOT NULL,
    counter_value   BIGINT        NOT NULL DEFAULT 0,
    window_start    TIMESTAMPTZ   NOT NULL,
    window_end      TIMESTAMPTZ   NOT NULL,
    CONSTRAINT chk_entity_type CHECK (entity_type IN ('USER', 'DEVICE', 'IP')),
    CONSTRAINT chk_counter_type CHECK (counter_type IN ('ORDERS_1H', 'ORDERS_24H', 'AMOUNT_24H', 'FAILED_PAYMENTS_1H')),
    CONSTRAINT uq_velocity_window UNIQUE (entity_type, entity_id, counter_type, window_start)
);

CREATE INDEX idx_velocity_entity ON velocity_counters (entity_type, entity_id);
