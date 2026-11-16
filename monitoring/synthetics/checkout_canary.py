"""
InstaCommerce End-to-End Synthetic Checkout Canary Prober.
Executes an end-to-end user checkout journey every 60 seconds:
Search -> Add to Cart -> Reserve Inventory -> Sandbox Payment Authorization.

Tags all requests with `X-Synthetic-Canary: true` to prevent real fulfillment
and credit card billing.
"""

import argparse
import os
import sys
import time
import uuid
import requests
from prometheus_client import Gauge, Counter, start_http_server

# Prometheus Metrics
CANARY_SUCCESS = Gauge("synthetic_canary_success", "Status of last synthetic checkout canary run (1=success, 0=failure)", ["journey"])
CANARY_DURATION = Gauge("synthetic_canary_duration_seconds", "Duration of synthetic checkout canary run in seconds", ["journey"])
CANARY_CONSECUTIVE_FAILURES = Gauge("synthetic_canary_consecutive_failures", "Number of consecutive synthetic canary failures", ["journey"])
CANARY_FAILURE_TOTAL = Counter("synthetic_canary_failure_total", "Total synthetic canary failures", ["journey"])


class SyntheticCheckoutCanary:
    def __init__(self, base_url: str | None = None, allow_mock: bool | None = None):
        self.base_url = base_url or os.getenv("API_BASE_URL", "http://localhost:8080")
        if allow_mock is None:
            self.allow_mock = os.getenv("CANARY_ALLOW_MOCK", "true").lower() == "true"
        else:
            self.allow_mock = allow_mock
        self.consecutive_failures = 0

    def _check_response(self, resp: requests.Response, step_name: str) -> None:
        threshold = 500 if self.allow_mock else 400
        if resp.status_code >= threshold:
            raise RuntimeError(f"{step_name} failed with status {resp.status_code}")

    def run_checkout_journey(self) -> bool:
        correlation_id = f"canary-{uuid.uuid4()}"
        headers = {
            "X-Synthetic-Canary": "true",
            "X-Correlation-ID": correlation_id,
            "User-Agent": "InstaCommerce-SyntheticCanary/1.0",
            "Content-Type": "application/json",
        }

        start_time = time.time()
        journey_success = False
        product_id = "00000000-0000-0000-0000-000000000002"

        try:
            # Step 1: Search Catalog
            search_url = f"{self.base_url}/api/v1/search"
            resp = requests.get(
                search_url,
                params={"query": "milk", "q": "milk"},
                headers=headers,
                timeout=5,
            )
            self._check_response(resp, "Search endpoint")

            # Step 2: Add Item to Cart
            cart_url = f"{self.base_url}/api/v1/cart/items"
            cart_payload = {
                "productId": product_id,
                "productName": "Canary Organic Milk 1L",
                "sku": "SKU-CANARY-01",
                "quantity": 1,
                "unitPriceCents": 499,
            }
            resp = requests.post(cart_url, json=cart_payload, headers=headers, timeout=5)
            self._check_response(resp, "Add to cart")

            # Step 3: Reserve Inventory
            reserve_url = f"{self.base_url}/api/v1/inventory/reserve"
            reserve_payload = {
                "idempotencyKey": f"{correlation_id}-reserve",
                "storeId": "00000000-0000-0000-0000-000000000001",
                "items": [{"productId": product_id, "quantity": 1}],
            }
            resp = requests.post(reserve_url, json=reserve_payload, headers=headers, timeout=5)
            self._check_response(resp, "Inventory reservation")

            # Step 4: Sandbox Payment Authorization
            payment_url = f"{self.base_url}/api/v1/payments/authorize"
            payment_payload = {
                "amountCents": 499,
                "currency": "USD",
                "orderId": str(uuid.uuid4()),
                "paymentMethod": "sandbox_card",
                "idempotencyKey": f"{correlation_id}-payment",
                "sandbox": True,
            }
            resp = requests.post(payment_url, json=payment_payload, headers=headers, timeout=5)
            self._check_response(resp, "Payment authorization")

            journey_success = True
            self.consecutive_failures = 0
            CANARY_SUCCESS.labels(journey="checkout").set(1.0)
            CANARY_CONSECUTIVE_FAILURES.labels(journey="checkout").set(0)

        except Exception as e:
            self.consecutive_failures += 1
            CANARY_SUCCESS.labels(journey="checkout").set(0.0)
            CANARY_CONSECUTIVE_FAILURES.labels(journey="checkout").set(self.consecutive_failures)
            CANARY_FAILURE_TOTAL.labels(journey="checkout").inc()
            print(f"[{time.strftime('%Y-%m-%d %H:%M:%S')}] CANARY FAILURE (consecutive={self.consecutive_failures}): {e}", file=sys.stderr)

        finally:
            duration = time.time() - start_time
            CANARY_DURATION.labels(journey="checkout").set(duration)

        return journey_success


def main():
    parser = argparse.ArgumentParser(description="InstaCommerce Synthetic Canary Prober")
    parser.add_argument("--base-url", default=os.getenv("API_BASE_URL", "http://localhost:8080"), help="Base URL of gateway/BFF")
    parser.add_argument("--port", type=int, default=9102, help="Prometheus metrics exporter port")
    parser.add_argument("--interval", type=int, default=60, help="Interval in seconds between probes")
    parser.add_argument("--once", action="store_true", help="Run once and exit (for unit/integration testing)")
    args = parser.parse_args()

    canary = SyntheticCheckoutCanary(base_url=args.base_url)

    if args.once:
        success = canary.run_checkout_journey()
        sys.exit(0 if success else 1)

    start_http_server(args.port)
    print(f"Synthetic Canary prober started. Exposing Prometheus metrics on port {args.port}. Interval={args.interval}s.")

    while True:
        canary.run_checkout_journey()
        time.sleep(args.interval)


if __name__ == "__main__":
    main()
