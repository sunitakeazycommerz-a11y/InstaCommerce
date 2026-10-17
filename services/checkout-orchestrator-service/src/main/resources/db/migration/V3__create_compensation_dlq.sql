CREATE TABLE IF NOT EXISTS compensation_dlq (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workflow_id VARCHAR(255) NOT NULL,
    activity_name VARCHAR(100) NOT NULL,
    target_id VARCHAR(255) NOT NULL,
    reason TEXT NOT NULL,
    payload TEXT,
    status VARCHAR(50) NOT NULL DEFAULT 'FAILED',
    retry_count INT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_compensation_dlq_workflow_id ON compensation_dlq(workflow_id);
CREATE INDEX IF NOT EXISTS idx_compensation_dlq_status ON compensation_dlq(status);
