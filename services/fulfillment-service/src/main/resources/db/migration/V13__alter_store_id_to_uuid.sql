-- Align store_id column type with warehouse-service, order-service, inventory-service, and rider-fleet-service.
-- Casts existing UUID strings directly and deterministically maps any legacy non-UUID strings to UUIDv3.

ALTER TABLE pick_tasks
    ALTER COLUMN store_id TYPE UUID
    USING CASE
        WHEN btrim(store_id) ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
            THEN btrim(store_id)::uuid
        ELSE encode(
            set_byte(
                set_byte(
                    decode(md5(btrim(store_id)), 'hex'),
                    6, (get_byte(decode(md5(btrim(store_id)), 'hex'), 6) & 15) | 48
                ),
                8, (get_byte(decode(md5(btrim(store_id)), 'hex'), 8) & 63) | 128
            ),
            'hex'
        )::uuid
    END;

ALTER TABLE riders
    ALTER COLUMN store_id TYPE UUID
    USING CASE
        WHEN btrim(store_id) ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
            THEN btrim(store_id)::uuid
        ELSE encode(
            set_byte(
                set_byte(
                    decode(md5(btrim(store_id)), 'hex'),
                    6, (get_byte(decode(md5(btrim(store_id)), 'hex'), 6) & 15) | 48
                ),
                8, (get_byte(decode(md5(btrim(store_id)), 'hex'), 8) & 63) | 128
            ),
            'hex'
        )::uuid
    END;

ALTER TABLE pick_batches
    ALTER COLUMN store_id TYPE UUID
    USING CASE
        WHEN btrim(store_id) ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
            THEN btrim(store_id)::uuid
        ELSE encode(
            set_byte(
                set_byte(
                    decode(md5(btrim(store_id)), 'hex'),
                    6, (get_byte(decode(md5(btrim(store_id)), 'hex'), 6) & 15) | 48
                ),
                8, (get_byte(decode(md5(btrim(store_id)), 'hex'), 8) & 63) | 128
            ),
            'hex'
        )::uuid
    END;
