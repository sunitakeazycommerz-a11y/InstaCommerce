from unittest.mock import patch, MagicMock
from monitoring.synthetics.checkout_canary import SyntheticCheckoutCanary, CANARY_SUCCESS, CANARY_CONSECUTIVE_FAILURES


def test_synthetic_canary_success():
    base_url = "http://mock-api.instacommerce.dev"
    canary = SyntheticCheckoutCanary(base_url=base_url)

    mock_resp_200 = MagicMock(status_code=200)
    mock_resp_201 = MagicMock(status_code=201)

    with patch("requests.get", return_value=mock_resp_200) as mock_get, \
         patch("requests.post", side_effect=[mock_resp_201, mock_resp_200, mock_resp_200]) as mock_post:

        success = canary.run_checkout_journey()

        assert success is True
        assert canary.consecutive_failures == 0
        assert CANARY_SUCCESS.labels(journey="checkout")._value.get() == 1.0
        assert CANARY_CONSECUTIVE_FAILURES.labels(journey="checkout")._value.get() == 0

        # Verify X-Synthetic-Canary header was sent
        get_headers = mock_get.call_args.kwargs["headers"]
        assert get_headers.get("X-Synthetic-Canary") == "true"
        assert "canary-" in get_headers.get("X-Correlation-ID")


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
