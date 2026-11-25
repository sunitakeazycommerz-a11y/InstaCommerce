-- Reverse ETL Orchestrator authoritative PostgreSQL schema

CREATE TABLE IF NOT EXISTS subscriptions (
    subscription_id VARCHAR(255) PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    description TEXT NOT NULL DEFAULT '',
    source_domain VARCHAR(100) NOT NULL,
    source_events JSONB NOT NULL DEFAULT '[]'::jsonb,
    sink_type VARCHAR(100) NOT NULL,
    sink_config TEXT NOT NULL DEFAULT '',
    transform_spec TEXT NOT NULL DEFAULT '',
    schedule VARCHAR(100) NOT NULL DEFAULT '',
    state VARCHAR(32) NOT NULL DEFAULT 'DRAFT' CHECK (state IN ('DRAFT', 'VALIDATED', 'ACTIVE', 'PAUSED', 'ARCHIVED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by VARCHAR(255) NOT NULL DEFAULT 'system'
);

ALTER TABLE subscriptions
    ADD COLUMN IF NOT EXISTS source_events JSONB NOT NULL DEFAULT '[]'::jsonb;

CREATE INDEX IF NOT EXISTS idx_subscriptions_source_domain ON subscriptions(source_domain);
CREATE INDEX IF NOT EXISTS idx_subscriptions_sink_type ON subscriptions(sink_type);
CREATE INDEX IF NOT EXISTS idx_subscriptions_state ON subscriptions(state);
CREATE INDEX IF NOT EXISTS idx_subscriptions_created_at ON subscriptions(created_at DESC);

CREATE TABLE IF NOT EXISTS reverse_etl_sync_audit (
    job_id VARCHAR(255) PRIMARY KEY,
    subscription_id VARCHAR(255) NOT NULL,
    sync_type VARCHAR(100) NOT NULL,
    rows_processed BIGINT NOT NULL DEFAULT 0,
    status VARCHAR(32) NOT NULL,
    duration_ms BIGINT NOT NULL DEFAULT 0,
    error_message TEXT NOT NULL DEFAULT '',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_reverse_etl_audit_subscription_created
    ON reverse_etl_sync_audit(subscription_id, created_at DESC);
