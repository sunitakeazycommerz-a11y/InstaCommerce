CREATE TABLE rider_shifts (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    rider_id    UUID         NOT NULL REFERENCES riders(id),
    shift_start TIMESTAMPTZ  NOT NULL,
    shift_end   TIMESTAMPTZ  NOT NULL,
    status      VARCHAR(20)  NOT NULL DEFAULT 'SCHEDULED'
);

CREATE INDEX idx_rider_shifts_rider_status ON rider_shifts (rider_id, status);
