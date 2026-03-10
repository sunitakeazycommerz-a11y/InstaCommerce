-- Optimistic-locking version column for refunds, mirroring V1 payments.version.
ALTER TABLE refunds ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
