-- Enforce append-only semantics at database level using rules.
-- The existing V1 migration revokes UPDATE/DELETE from audit_app role,
-- but rules provide an additional safety net regardless of which role connects.

CREATE OR REPLACE RULE prevent_audit_update AS ON UPDATE TO audit_events DO INSTEAD NOTHING;
CREATE OR REPLACE RULE prevent_audit_delete AS ON DELETE TO audit_events DO INSTEAD NOTHING;

-- Index on created_at for efficient time-range queries.
-- V1 already creates idx_audit_created on (created_at), so this is a no-op guard.
-- CREATE INDEX IF NOT EXISTS idx_audit_created ON audit_events (created_at);

-- Index on (resource_type, resource_id) for entity-based lookups.
-- V1 already creates idx_audit_resource on (resource_type, resource_id), so this is a no-op guard.
-- CREATE INDEX IF NOT EXISTS idx_audit_resource ON audit_events (resource_type, resource_id);
