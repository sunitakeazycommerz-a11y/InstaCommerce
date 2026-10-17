-- Reconcile DeliveryStatus enum with canonical routing-eta states
ALTER TYPE delivery_status ADD VALUE IF NOT EXISTS 'PENDING';
ALTER TYPE delivery_status ADD VALUE IF NOT EXISTS 'RIDER_ASSIGNED';
ALTER TYPE delivery_status ADD VALUE IF NOT EXISTS 'EN_ROUTE';
ALTER TYPE delivery_status ADD VALUE IF NOT EXISTS 'NEAR_DESTINATION';
