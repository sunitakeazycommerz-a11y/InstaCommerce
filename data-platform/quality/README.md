# InstaCommerce Data Quality Platform

Authoritative documentation for Great Expectations test suites, data assertions, and offline CI quality fixtures in InstaCommerce.

---

## 1. Quality Architecture

Data quality checks operate in two distinct modes:
1. **CI Offline Fixture Mode:** Validates JSON expectation suites against local deterministic test fixtures in `fixtures/*.json`. Requires zero network access or GCP credentials.
2. **Production Pipeline Mode:** Validates live Google BigQuery datasets during Airflow orchestration runs (`data_quality` DAG).

---

## 2. Test Suites Inventory (5 Production Suites)

| Suite Name | Target Table | Fixture File | Key Expectations |
|---|---|---|---|
| `orders_validation` | `analytics.orders` | `fixtures/orders.json` | `order_id` non-null unique, `total_amount` positive, valid status enum |
| `payments_validation` | `analytics.payments` | `fixtures/payments.json` | `payment_id` non-null unique, ISO-4217 currency, valid PSP reference |
| `inventory_validation` | `analytics.inventory` | `fixtures/inventory.json` | `sku` and `store_id` non-null, `quantity_available` >= 0 |
| `users_validation` | `analytics.users` | `fixtures/users.json` | Valid email regex, non-null user UUID, normalized phone format |
| `daily_sales_validation` | `marts.mart_daily_sales`| `fixtures/daily_sales.json` | `sale_date` valid ISO date, `store_id` non-null, `daily_revenue` >= 0 |

---

## 3. Running Quality Checks

```bash
# Run all suites in offline fixture mode (CI safe)
cd data-platform/quality
python run_quality_checks.py --suite all --fixtures

# Run specific suite
python run_quality_checks.py --suite daily_sales --fixtures

# Run quality checks unit tests
pytest test_quality_checks.py -v
```
