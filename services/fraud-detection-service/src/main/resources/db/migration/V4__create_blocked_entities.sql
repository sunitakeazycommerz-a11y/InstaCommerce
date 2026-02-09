CREATE TABLE blocked_entities (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    entity_type     VARCHAR(20)   NOT NULL,
    entity_value    VARCHAR(255)  NOT NULL,
    reason          TEXT,
    blocked_by      VARCHAR(255),
    blocked_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    expires_at      TIMESTAMPTZ,
    active          BOOLEAN       NOT NULL DEFAULT true,
    CONSTRAINT chk_blocked_entity_type CHECK (entity_type IN ('USER', 'DEVICE', 'IP', 'PHONE')),
    CONSTRAINT uq_blocked_entity UNIQUE (entity_type, entity_value, active)
);

CREATE INDEX idx_blocked_entity_lookup ON blocked_entities (entity_type, entity_value, active);
