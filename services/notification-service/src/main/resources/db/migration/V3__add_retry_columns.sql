ALTER TABLE notification_log ADD COLUMN retry_count INTEGER NOT NULL DEFAULT 0;
ALTER TABLE notification_log ADD COLUMN next_retry_at TIMESTAMPTZ;
ALTER TABLE notification_log ADD COLUMN event_type VARCHAR(50);
ALTER TABLE notification_log ADD COLUMN subject VARCHAR(500);
ALTER TABLE notification_log ADD COLUMN body TEXT;

CREATE INDEX idx_notification_retry ON notification_log (status, next_retry_at)
    WHERE status = 'RETRY_PENDING';
