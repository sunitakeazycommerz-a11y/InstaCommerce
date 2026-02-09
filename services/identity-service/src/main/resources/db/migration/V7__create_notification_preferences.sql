CREATE TABLE notification_preferences (
    user_id           UUID PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    email_opt_out     BOOLEAN     NOT NULL DEFAULT false,
    sms_opt_out       BOOLEAN     NOT NULL DEFAULT false,
    push_opt_out      BOOLEAN     NOT NULL DEFAULT false,
    marketing_opt_out BOOLEAN     NOT NULL DEFAULT false,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);
