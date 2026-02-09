ALTER TABLE fraud_rules
    DROP CONSTRAINT IF EXISTS chk_action;

ALTER TABLE fraud_rules
    ADD CONSTRAINT chk_action CHECK (action IN ('ALLOW', 'FLAG', 'BLOCK', 'REVIEW'));

ALTER TABLE blocked_entities
    DROP CONSTRAINT IF EXISTS uq_blocked_entity;

DROP INDEX IF EXISTS uq_blocked_entity_active;

CREATE UNIQUE INDEX uq_blocked_entity_active
    ON blocked_entities (entity_type, entity_value)
    WHERE active = true;
