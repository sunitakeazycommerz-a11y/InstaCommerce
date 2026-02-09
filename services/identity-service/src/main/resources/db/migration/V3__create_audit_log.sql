CREATE TABLE audit_log (
    id          BIGSERIAL PRIMARY KEY,
    actor_id    UUID,
    action      VARCHAR(50)  NOT NULL,
    target_type VARCHAR(50),
    target_id   VARCHAR(255),
    metadata    JSONB,
    ip_address  INET,
    trace_id    VARCHAR(32),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_audit_log_actor   ON audit_log (actor_id);
CREATE INDEX idx_audit_log_action  ON audit_log (action);
CREATE INDEX idx_audit_log_created ON audit_log (created_at);
