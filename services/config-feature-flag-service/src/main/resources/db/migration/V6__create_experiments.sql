CREATE TABLE experiments (
    id                          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    key                         VARCHAR(120)  NOT NULL,
    name                        VARCHAR(255),
    description                 TEXT,
    status                      VARCHAR(20)   NOT NULL DEFAULT 'DRAFT',
    assignment_unit             VARCHAR(50),
    start_at                    TIMESTAMPTZ,
    end_at                      TIMESTAMPTZ,
    switchback_enabled          BOOLEAN       NOT NULL DEFAULT false,
    switchback_interval_minutes INT,
    switchback_start_at         TIMESTAMPTZ,
    metadata                    JSONB,
    created_by                  VARCHAR(255),
    created_at                  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at                  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    version                     BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT uq_experiments_key UNIQUE (key),
    CONSTRAINT chk_experiment_status CHECK (status IN ('DRAFT', 'RUNNING', 'PAUSED', 'COMPLETED')),
    CONSTRAINT chk_switchback_interval CHECK (switchback_interval_minutes IS NULL OR switchback_interval_minutes > 0)
);

CREATE INDEX idx_experiments_key ON experiments (key);
CREATE INDEX idx_experiments_status ON experiments (status);

CREATE TABLE experiment_variants (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    experiment_id   UUID        NOT NULL REFERENCES experiments(id) ON DELETE CASCADE,
    name            VARCHAR(100) NOT NULL,
    weight          INT         NOT NULL DEFAULT 0,
    payload         JSONB,
    is_control      BOOLEAN     NOT NULL DEFAULT false,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_experiment_variants_name UNIQUE (experiment_id, name),
    CONSTRAINT chk_experiment_variant_weight CHECK (weight >= 0)
);

CREATE INDEX idx_experiment_variants_experiment_id ON experiment_variants (experiment_id);

CREATE TABLE experiment_exposures (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    experiment_id      UUID         NOT NULL REFERENCES experiments(id) ON DELETE CASCADE,
    variant_id         UUID         REFERENCES experiment_variants(id) ON DELETE SET NULL,
    variant_name       VARCHAR(100) NOT NULL,
    user_id            UUID,
    assignment_key     VARCHAR(255),
    switchback_window  BIGINT,
    source             VARCHAR(30),
    context            JSONB,
    exposed_at         TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_experiment_exposures_experiment_id ON experiment_exposures (experiment_id, exposed_at DESC);
CREATE INDEX idx_experiment_exposures_user_id ON experiment_exposures (user_id);
