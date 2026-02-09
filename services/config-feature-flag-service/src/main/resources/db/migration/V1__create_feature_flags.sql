CREATE EXTENSION IF NOT EXISTS "pgcrypto";

CREATE TABLE feature_flags (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    key                 VARCHAR(100)  NOT NULL,
    name                VARCHAR(255),
    description         TEXT,
    flag_type           VARCHAR(20)   NOT NULL DEFAULT 'BOOLEAN',
    enabled             BOOLEAN       NOT NULL DEFAULT false,
    default_value       TEXT,
    rollout_percentage  INT           NOT NULL DEFAULT 0,
    target_users        JSONB,
    metadata            JSONB,
    created_by          VARCHAR(255),
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    version             BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT uq_feature_flags_key UNIQUE (key),
    CONSTRAINT chk_flag_type CHECK (flag_type IN ('BOOLEAN', 'PERCENTAGE', 'USER_LIST', 'JSON')),
    CONSTRAINT chk_rollout_percentage CHECK (rollout_percentage >= 0 AND rollout_percentage <= 100)
);

CREATE INDEX idx_feature_flags_key ON feature_flags (key);
CREATE INDEX idx_feature_flags_enabled ON feature_flags (enabled) WHERE enabled = true;
