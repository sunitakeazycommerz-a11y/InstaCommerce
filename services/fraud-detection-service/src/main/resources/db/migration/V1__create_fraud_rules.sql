CREATE TABLE fraud_rules (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name            VARCHAR(255)  NOT NULL,
    rule_type       VARCHAR(50)   NOT NULL,
    condition_json  JSONB         NOT NULL,
    score_impact    INT           NOT NULL,
    action          VARCHAR(20)   NOT NULL DEFAULT 'FLAG',
    active          BOOLEAN       NOT NULL DEFAULT true,
    priority        INT           NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT chk_rule_type CHECK (rule_type IN ('VELOCITY', 'AMOUNT', 'DEVICE', 'GEO', 'PATTERN')),
    CONSTRAINT chk_action CHECK (action IN ('FLAG', 'BLOCK', 'REVIEW'))
);

CREATE INDEX idx_fraud_rules_active_type ON fraud_rules (active, rule_type);
