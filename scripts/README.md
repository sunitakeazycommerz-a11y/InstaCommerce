# Utility Scripts

Development and operations scripts for the InstaCommerce platform.

## Available Scripts

| Script | Description |
|--------|-------------|
| `init-dbs.sql` | Initializes all PostgreSQL databases for local development. Creates databases for all 17 microservices (identity, catalog, inventory, order, payment, fulfillment, notification, search, pricing, cart, warehouse, rider, routing, wallet, audit, fraud, config). |
| `lsp-walkthrough` | Unified CLI for code walkthroughs, symbol outlines, definitions, and references across Java (`jdtls`), Python (`pyright`), and Go (`gopls`). |
| `check-monorepo-consistency.sh` | Automated drift and consistency scanner for services, CI matrices, Helm values, Flyway migrations, Dockerfiles, and skills. |

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

### LSP Code Walkthrough CLI

Use `scripts/lsp-walkthrough` to inspect language server status, generate structural symbol outlines, and perform architectural tours across Java, Go, and Python services:

```bash
# Check status and paths of gopls, pyright, and jdtls
./scripts/lsp-walkthrough status

# Outline any source file (classes, methods, endpoints, events)
./scripts/lsp-walkthrough outline services/order-service/src/main/java/com/instacommerce/order/controller/OrderController.java
./scripts/lsp-walkthrough outline services/outbox-relay-service/main.go
./scripts/lsp-walkthrough outline services/ai-inference-service/app/main.py

# In-depth architectural code walkthrough of a file
./scripts/lsp-walkthrough walkthrough services/checkout-orchestrator-service/src/main/java/com/instacommerce/checkout/workflow/CheckoutWorkflowImpl.java

# Microservice structural tour
./scripts/lsp-walkthrough service order-service

# Jump to symbol definition via LSP (Go: gopls)
./scripts/lsp-walkthrough def services/outbox-relay-service/main.go 465 20

# Find references via LSP (Go: gopls)
./scripts/lsp-walkthrough refs services/outbox-relay-service/main.go 73 6
```

## Adding a New Script

1. Place the script in the `scripts/` directory
2. Add an entry to the table above with a description
3. Include usage instructions in this README

