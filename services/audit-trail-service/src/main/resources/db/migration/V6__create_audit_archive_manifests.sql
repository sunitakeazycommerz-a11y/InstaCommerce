CREATE TABLE audit_archive_manifests (
    id                         UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    archive_year               INTEGER       NOT NULL,
    archive_month              INTEGER       NOT NULL,
    s3_glacier_path            VARCHAR(512)  NOT NULL,
    row_count                  BIGINT        NOT NULL,
    compressed_size_bytes      BIGINT        NOT NULL,
    checksum_sha256            VARCHAR(64)   NOT NULL,
    date_range_start           TIMESTAMPTZ   NOT NULL,
    date_range_end             TIMESTAMPTZ   NOT NULL,
    archived_at                TIMESTAMPTZ   NOT NULL,
    retrievable_until          TIMESTAMPTZ   NOT NULL,
    verification_status        VARCHAR(50)   NOT NULL,
    creation_method            VARCHAR(50),
    last_verification_at       TIMESTAMPTZ,
    verification_attempt_count INTEGER,
    first_sequence             BIGINT,
    last_sequence              BIGINT,
    last_event_hash            VARCHAR(64)
);

CREATE INDEX idx_archive_manifests_status ON audit_archive_manifests (verification_status);
CREATE INDEX idx_archive_manifests_range ON audit_archive_manifests (date_range_start, date_range_end);
