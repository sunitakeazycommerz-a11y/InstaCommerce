DO $$
DECLARE
    start_date date := date_trunc('month', now())::date;
    end_date date;
    partition_name text;
BEGIN
    FOR i IN 0..5 LOOP
        end_date := (start_date + interval '1 month')::date;
        partition_name := format('delivery_tracking_%s', to_char(start_date, 'YYYY_MM'));
        EXECUTE format(
            'CREATE TABLE IF NOT EXISTS %I PARTITION OF delivery_tracking FOR VALUES FROM (%L) TO (%L)',
            partition_name, start_date, end_date);
        start_date := end_date;
    END LOOP;
END $$;
