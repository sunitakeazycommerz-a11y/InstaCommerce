ALTER TABLE velocity_counters
    DROP CONSTRAINT IF EXISTS chk_counter_type;

ALTER TABLE velocity_counters
    ADD CONSTRAINT chk_counter_type
    CHECK (counter_type IN (
        'ORDERS_5M',
        'ORDERS_1H',
        'ORDERS_24H',
        'AMOUNT_24H',
        'FAILED_PAYMENTS_5M',
        'FAILED_PAYMENTS_1H',
        'CHARGEBACKS_30D',
        'REFUND_FAILURES_24H'
    ));
