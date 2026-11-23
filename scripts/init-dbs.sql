-- InstaCommerce Multi-Service Database Initialization
-- Creates all microservice databases and local dev roles for docker-compose.
-- Includes both default database names and *_db aliases for complete compatibility.

DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'reconciliation') THEN
        CREATE ROLE reconciliation WITH LOGIN PASSWORD 'devpass';
    END IF;
END $$;

-- Core Commerce & Sagas
CREATE DATABASE identity_db;
CREATE DATABASE orders;
CREATE DATABASE order_db;
CREATE DATABASE carts;
CREATE DATABASE cart_db;
CREATE DATABASE payments;
CREATE DATABASE payment_db;
CREATE DATABASE checkout;
CREATE DATABASE checkout_db;
CREATE DATABASE catalog;
CREATE DATABASE catalog_db;
CREATE DATABASE inventory;
CREATE DATABASE inventory_db;
CREATE DATABASE pricing;
CREATE DATABASE pricing_db;

-- Fulfillment & Fleet
CREATE DATABASE fulfillment;
CREATE DATABASE fulfillment_db;
CREATE DATABASE warehouse;
CREATE DATABASE warehouse_db;
CREATE DATABASE rider_fleet;
CREATE DATABASE rider_db;
CREATE DATABASE routing;
CREATE DATABASE routing_db;

-- Search, Notification & Engagement
CREATE DATABASE search;
CREATE DATABASE search_db;
CREATE DATABASE notification;
CREATE DATABASE notification_db;
CREATE DATABASE wallets;
CREATE DATABASE wallet_db;

-- Platform, Governance & Ops
CREATE DATABASE audit_db;
CREATE DATABASE audit;
CREATE DATABASE fraud;
CREATE DATABASE fraud_db;
CREATE DATABASE feature_flags_db;
CREATE DATABASE config_db;
CREATE DATABASE reconciliation OWNER reconciliation;
CREATE DATABASE reconciliation_db OWNER reconciliation;
CREATE DATABASE reverse_etl;
CREATE DATABASE reverse_etl_db;

GRANT ALL PRIVILEGES ON DATABASE reconciliation TO reconciliation;
GRANT ALL PRIVILEGES ON DATABASE reconciliation_db TO reconciliation;
