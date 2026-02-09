CREATE EXTENSION IF NOT EXISTS "pgcrypto";

CREATE TABLE audit_events (
    id              UUID            NOT NULL DEFAULT gen_random_uuid(),
    event_type      VARCHAR(100)    NOT NULL,
    source_service  VARCHAR(50)     NOT NULL,
    actor_id        UUID,
    actor_type      VARCHAR(20),
    resource_type   VARCHAR(100),
    resource_id     VARCHAR(255),
    action          VARCHAR(100)    NOT NULL,
    details         JSONB,
    ip_address      VARCHAR(45),
    user_agent      VARCHAR(512),
    correlation_id  VARCHAR(64),
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT now(),
    CONSTRAINT pk_audit_events PRIMARY KEY (id, created_at),
    CONSTRAINT chk_actor_type CHECK (actor_type IN ('USER', 'SYSTEM', 'ADMIN'))
) PARTITION BY RANGE (created_at);

-- Indexes on the partitioned table (automatically applied to each partition)
CREATE INDEX idx_audit_source_created ON audit_events (source_service, created_at);
CREATE INDEX idx_audit_actor_created ON audit_events (actor_id, created_at);
CREATE INDEX idx_audit_resource ON audit_events (resource_type, resource_id);
CREATE INDEX idx_audit_created ON audit_events (created_at);
CREATE INDEX idx_audit_event_type ON audit_events (event_type);
CREATE INDEX idx_audit_correlation ON audit_events (correlation_id) WHERE correlation_id IS NOT NULL;

-- Revoke UPDATE and DELETE from the application role to enforce append-only semantics
REVOKE UPDATE, DELETE ON audit_events FROM audit_app;
