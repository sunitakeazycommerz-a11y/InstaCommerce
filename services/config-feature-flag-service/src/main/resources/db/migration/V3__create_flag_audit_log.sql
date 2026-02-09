CREATE TABLE flag_audit_log (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    flag_id     UUID          NOT NULL REFERENCES feature_flags(id) ON DELETE CASCADE,
    action      VARCHAR(50)   NOT NULL,
    old_value   TEXT,
    new_value   TEXT,
    changed_by  VARCHAR(255),
    changed_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT chk_audit_action CHECK (action IN ('CREATED', 'UPDATED', 'ENABLED', 'DISABLED', 'OVERRIDE_ADDED'))
);

CREATE INDEX idx_flag_audit_log_flag_changed ON flag_audit_log (flag_id, changed_at DESC);
