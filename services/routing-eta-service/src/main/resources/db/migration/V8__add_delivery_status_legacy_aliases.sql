-- Reconcile DeliveryStatus enum with fulfillment-service legacy states
ALTER TYPE delivery_status ADD VALUE IF NOT EXISTS 'ASSIGNED';
ALTER TYPE delivery_status ADD VALUE IF NOT EXISTS 'IN_TRANSIT';
