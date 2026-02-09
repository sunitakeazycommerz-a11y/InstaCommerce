CREATE TABLE referral_codes (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID         NOT NULL UNIQUE,
    code        VARCHAR(10)  NOT NULL UNIQUE,
    uses        INT          NOT NULL DEFAULT 0,
    max_uses    INT          NOT NULL DEFAULT 10,
    reward_cents BIGINT      NOT NULL DEFAULT 5000,
    active      BOOLEAN      NOT NULL DEFAULT true
);

CREATE INDEX idx_referral_codes_code ON referral_codes (code);

CREATE TABLE referral_redemptions (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    referral_code_id  UUID        NOT NULL REFERENCES referral_codes(id),
    referred_user_id  UUID        NOT NULL UNIQUE,
    reward_credited   BOOLEAN     NOT NULL DEFAULT false,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);
