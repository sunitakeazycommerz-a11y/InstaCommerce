CREATE TABLE IF NOT EXISTS reservation_release_logs (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    reservation_id UUID         NOT NULL REFERENCES reservations(id) ON DELETE CASCADE,
    release_key    VARCHAR(128) NOT NULL,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_reservation_release_key UNIQUE (reservation_id, release_key)
);

CREATE INDEX IF NOT EXISTS idx_reservation_release_logs_res ON reservation_release_logs (reservation_id);
