from unittest.mock import MagicMock, patch

from monitoring.synthetics.checkout_canary import (
    CANARY_CONSECUTIVE_FAILURES,
    CANARY_SUCCESS,
    SyntheticCheckoutCanary,
)


def test_synthetic_canary_success_and_contract_payloads():
    base_url = "http://mock-api.instacommerce.dev"
    canary = SyntheticCheckoutCanary(base_url=base_url, allow_mock=False)

    mock_resp_200 = MagicMock(status_code=200)
    mock_resp_201 = MagicMock(status_code=201)

    with (
        patch("requests.get", return_value=mock_resp_200) as mock_get,
        patch("requests.post", side_effect=[mock_resp_201, mock_resp_200, mock_resp_200]) as mock_post,
    ):
        success = canary.run_checkout_journey()

        assert success is True
        assert canary.consecutive_failures == 0
        assert CANARY_SUCCESS.labels(journey="checkout")._value.get() == 1.0
        assert CANARY_CONSECUTIVE_FAILURES.labels(journey="checkout")._value.get() == 0

        get_headers = mock_get.call_args.kwargs["headers"]
        assert get_headers.get("X-Synthetic-Canary") == "true"
        assert "canary-" in get_headers.get("X-Correlation-ID")
        assert mock_get.call_args.kwargs["params"] == {"query": "milk", "q": "milk"}

        assert mock_post.call_count == 3
        cart_call, reserve_call, payment_call = mock_post.call_args_list

        assert cart_call.args[0] == f"{base_url}/api/v1/cart/items"
        cart_json = cart_call.kwargs["json"]
        for key in ("productId", "productName", "sku", "quantity", "unitPriceCents"):
            assert key in cart_json

        assert reserve_call.args[0] == f"{base_url}/api/v1/inventory/reserve"
        reserve_json = reserve_call.kwargs["json"]
        assert "idempotencyKey" in reserve_json
        assert "storeId" in reserve_json
        assert reserve_json["items"][0]["quantity"] == 1
        assert "productId" in reserve_json["items"][0]

        assert payment_call.args[0] == f"{base_url}/api/v1/payments/authorize"
        payment_json = payment_call.kwargs["json"]
        for key in ("amountCents", "currency", "orderId", "paymentMethod", "idempotencyKey"):
            assert key in payment_json


def test_synthetic_canary_failure_increments_consecutive_counter():
    base_url = "http://mock-api.instacommerce.dev"
    canary = SyntheticCheckoutCanary(base_url=base_url)

    mock_resp_503 = MagicMock(status_code=503)

    with patch("requests.get", return_value=mock_resp_503):
        success1 = canary.run_checkout_journey()
        assert success1 is False
        assert canary.consecutive_failures == 1
        assert CANARY_SUCCESS.labels(journey="checkout")._value.get() == 0.0
        assert CANARY_CONSECUTIVE_FAILURES.labels(journey="checkout")._value.get() == 1

        success2 = canary.run_checkout_journey()
        assert success2 is False
        assert canary.consecutive_failures == 2
        assert CANARY_CONSECUTIVE_FAILURES.labels(journey="checkout")._value.get() == 2


def test_synthetic_canary_live_mode_fails_on_404_while_mock_mode_passes(monkeypatch):
    base_url = "http://mock-api.instacommerce.dev"
    mock_resp_404 = MagicMock(status_code=404)

    monkeypatch.setenv("CANARY_ALLOW_MOCK", "false")
    live_canary = SyntheticCheckoutCanary(base_url=base_url)
    assert live_canary.allow_mock is False

    with patch("requests.get", return_value=mock_resp_404):
        assert live_canary.run_checkout_journey() is False
        assert live_canary.consecutive_failures == 1

    monkeypatch.setenv("CANARY_ALLOW_MOCK", "true")
    mock_canary = SyntheticCheckoutCanary(base_url=base_url)
    assert mock_canary.allow_mock is True

    with (
        patch("requests.get", return_value=mock_resp_404),
        patch("requests.post", return_value=mock_resp_404),
    ):
        assert mock_canary.run_checkout_journey() is True
        assert mock_canary.consecutive_failures == 0
