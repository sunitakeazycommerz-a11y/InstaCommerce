CREATE EXTENSION IF NOT EXISTS "pgcrypto";

CREATE TABLE users (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email          VARCHAR(255) NOT NULL,
    password_hash  VARCHAR(72)  NOT NULL,
    roles          VARCHAR[]    NOT NULL DEFAULT '{CUSTOMER}',
    status         VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version        BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT uq_users_email UNIQUE (email),
    CONSTRAINT chk_users_status CHECK (status IN ('ACTIVE','SUSPENDED','DELETED'))
);

CREATE INDEX idx_users_email ON users (email);
CREATE INDEX idx_users_status ON users (status) WHERE status = 'ACTIVE';
