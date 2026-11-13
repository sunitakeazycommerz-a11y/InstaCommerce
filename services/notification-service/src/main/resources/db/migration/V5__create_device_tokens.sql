CREATE TABLE device_tokens (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID         NOT NULL,
    token       VARCHAR(512) NOT NULL,
    platform    VARCHAR(20)  NOT NULL DEFAULT 'UNKNOWN',
    active      BOOLEAN      NOT NULL DEFAULT true,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uq_device_tokens_token ON device_tokens (token);
CREATE INDEX idx_device_tokens_user_active ON device_tokens (user_id, active, updated_at DESC);
