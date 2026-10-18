CREATE INDEX IF NOT EXISTS idx_outbox_events_sent_created_at ON outbox_events (sent, created_at);
