ALTER TABLE audit_events ADD COLUMN IF NOT EXISTS hash_version INTEGER NOT NULL DEFAULT 1;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'audit_app') THEN
        REVOKE UPDATE, DELETE ON audit_events FROM audit_app;
    END IF;
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'audit_reader') THEN
        REVOKE UPDATE, DELETE, INSERT ON audit_events FROM audit_reader;
    END IF;
END $$;
