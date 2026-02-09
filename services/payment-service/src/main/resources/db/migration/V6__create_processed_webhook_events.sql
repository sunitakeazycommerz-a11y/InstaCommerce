CREATE TABLE processed_webhook_events (
    event_id     VARCHAR(255) PRIMARY KEY,
    processed_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);
