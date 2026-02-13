# Utility Scripts

Development and operations scripts for the InstaCommerce platform.

## Available Scripts

| Script | Description |
|--------|-------------|
| `init-dbs.sql` | Initializes all PostgreSQL databases for local development. Creates databases for all 17 microservices (identity, catalog, inventory, order, payment, fulfillment, notification, search, pricing, cart, warehouse, rider, routing, wallet, audit, fraud, config). |

## Usage

### Database Initialization

Run `init-dbs.sql` against a local PostgreSQL instance to set up all service databases:

```bash
# Using psql
psql -U postgres -f scripts/init-dbs.sql

# Using Docker Compose (if postgres is running via docker-compose.yml)
docker exec -i instacommerce-postgres psql -U postgres < scripts/init-dbs.sql
```

### Databases Created

The init script creates the following databases:

| Database | Service |
|----------|---------|
| `identity_db` | identity-service |
| `catalog_db` | catalog-service |
| `inventory_db` | inventory-service |
| `order_db` | order-service |
| `payment_db` | payment-service |
| `fulfillment_db` | fulfillment-service |
| `notification_db` | notification-service |
| `search_db` | search-service |
| `pricing_db` | pricing-service |
| `cart_db` | cart-service |
| `warehouse_db` | warehouse-service |
| `rider_db` | rider-fleet-service |
| `routing_db` | routing-eta-service |
| `wallet_db` | wallet-loyalty-service |
| `audit_db` | audit-trail-service |
| `fraud_db` | fraud-detection-service |
| `config_db` | config-feature-flag-service |

## Adding a New Script

1. Place the script in the `scripts/` directory
2. Add an entry to the table above with a description
3. Include usage instructions in this README
