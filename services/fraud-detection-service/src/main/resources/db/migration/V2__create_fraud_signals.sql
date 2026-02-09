CREATE TABLE fraud_signals (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id             UUID          NOT NULL,
    order_id            UUID          NOT NULL,
    device_fingerprint  VARCHAR(255),
    ip_address          VARCHAR(45),
    score               INT           NOT NULL DEFAULT 0,
    risk_level          VARCHAR(20)   NOT NULL,
    rules_triggered     JSONB,
    action_taken        VARCHAR(20)   NOT NULL,
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT chk_risk_level CHECK (risk_level IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),
    CONSTRAINT chk_signal_action CHECK (action_taken IN ('ALLOW', 'FLAG', 'REVIEW', 'BLOCK'))
);

CREATE INDEX idx_fraud_signals_user_created ON fraud_signals (user_id, created_at DESC);
CREATE INDEX idx_fraud_signals_device ON fraud_signals (device_fingerprint);
CREATE INDEX idx_fraud_signals_ip ON fraud_signals (ip_address);
