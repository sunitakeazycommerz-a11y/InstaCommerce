CREATE TABLE notification_log (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id      UUID         NOT NULL,
    event_id     VARCHAR(64)  NOT NULL,
    channel      VARCHAR(10)  NOT NULL,
    template_id  VARCHAR(50)  NOT NULL,
    recipient    VARCHAR(255) NOT NULL,
    status       VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    provider_ref VARCHAR(255),
    attempts     INT          NOT NULL DEFAULT 0,
    last_error   TEXT,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    sent_at      TIMESTAMPTZ,
    CONSTRAINT uq_notification_dedup UNIQUE (event_id, channel)
);

CREATE INDEX idx_notification_user ON notification_log (user_id, created_at);
