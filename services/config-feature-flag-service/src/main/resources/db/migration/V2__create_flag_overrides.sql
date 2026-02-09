CREATE TABLE flag_overrides (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    flag_id         UUID          NOT NULL REFERENCES feature_flags(id) ON DELETE CASCADE,
    user_id         UUID          NOT NULL,
    override_value  TEXT          NOT NULL,
    reason          VARCHAR(500),
    created_by      VARCHAR(255),
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    expires_at      TIMESTAMPTZ,
    CONSTRAINT uq_flag_overrides_flag_user UNIQUE (flag_id, user_id)
);

CREATE INDEX idx_flag_overrides_flag_user ON flag_overrides (flag_id, user_id);
