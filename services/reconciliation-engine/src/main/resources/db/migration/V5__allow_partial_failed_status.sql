-- V5: Allow PARTIAL_FAILED status emitted when individual mismatches fail during a run
ALTER TABLE reconciliation_runs
    DROP CONSTRAINT IF EXISTS reconciliation_runs_status_check;

ALTER TABLE reconciliation_runs
    ADD CONSTRAINT reconciliation_runs_status_check
    CHECK (status IN ('PENDING', 'IN_PROGRESS', 'COMPLETED', 'PARTIAL_FAILED', 'FAILED'));
