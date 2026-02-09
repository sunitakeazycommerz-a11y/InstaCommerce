-- Function to automatically create monthly partitions for audit_events
CREATE OR REPLACE FUNCTION create_audit_partition(partition_date DATE)
RETURNS VOID AS $$
DECLARE
    partition_name TEXT;
    start_date     DATE;
    end_date       DATE;
BEGIN
    start_date     := date_trunc('month', partition_date);
    end_date       := start_date + INTERVAL '1 month';
    partition_name := 'audit_events_' || to_char(start_date, 'YYYY_MM');

    IF NOT EXISTS (
        SELECT 1 FROM pg_class WHERE relname = partition_name
    ) THEN
        EXECUTE format(
            'CREATE TABLE %I PARTITION OF audit_events FOR VALUES FROM (%L) TO (%L)',
            partition_name, start_date, end_date
        );
        RAISE NOTICE 'Created partition: %', partition_name;
    END IF;
END;
$$ LANGUAGE plpgsql;

-- Function to create partitions for the next N months
CREATE OR REPLACE FUNCTION ensure_future_audit_partitions(months_ahead INT DEFAULT 3)
RETURNS VOID AS $$
DECLARE
    i INT;
BEGIN
    FOR i IN 0..months_ahead LOOP
        PERFORM create_audit_partition(
            (date_trunc('month', CURRENT_DATE) + (i || ' months')::INTERVAL)::DATE
        );
    END LOOP;
END;
$$ LANGUAGE plpgsql;

-- Create partition for current month and next 3 months
SELECT ensure_future_audit_partitions(3);
