ALTER TABLE flag_audit_log
    DROP CONSTRAINT IF EXISTS chk_audit_action;

ALTER TABLE flag_audit_log
    ADD CONSTRAINT chk_audit_action CHECK (
        action IN ('CREATED', 'UPDATED', 'ENABLED', 'DISABLED', 'OVERRIDE_ADDED', 'OVERRIDE_REMOVED')
    );
