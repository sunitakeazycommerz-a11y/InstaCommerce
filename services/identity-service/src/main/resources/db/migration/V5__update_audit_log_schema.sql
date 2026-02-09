ALTER TABLE audit_log RENAME COLUMN actor_id TO user_id;
ALTER TABLE audit_log RENAME COLUMN target_type TO entity_type;
ALTER TABLE audit_log RENAME COLUMN target_id TO entity_id;
ALTER TABLE audit_log RENAME COLUMN metadata TO details;

ALTER TABLE audit_log ALTER COLUMN action TYPE VARCHAR(100);
ALTER TABLE audit_log ALTER COLUMN entity_id TYPE VARCHAR(100);

ALTER TABLE audit_log ADD COLUMN IF NOT EXISTS user_agent TEXT;

CREATE INDEX IF NOT EXISTS idx_audit_user_id ON audit_log (user_id);
CREATE INDEX IF NOT EXISTS idx_audit_action ON audit_log (action);
CREATE INDEX IF NOT EXISTS idx_audit_created_at ON audit_log (created_at);
