package com.instacommerce.payment.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.instacommerce.payment.exception.PaymentGatewayException;
import com.stripe.model.PaymentIntent;
import com.stripe.net.RequestOptions;
import com.stripe.param.PaymentIntentCaptureParams;
import java.util.Collections;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

class StripePaymentGatewayTest {

    @Test
    @DisplayName("capture passes null idempotencyKey to retrieve and mutating key to capture")
    void captureDoesNotReuseIdempotencyKeyOnRetrieve() throws Exception {
        StripePaymentGateway gateway = new StripePaymentGateway("sk_test_real_key", 5000, 15000, false);
        PaymentIntent mockIntent = mock(PaymentIntent.class);

        try (MockedStatic<PaymentIntent> paymentIntentMock = Mockito.mockStatic(PaymentIntent.class)) {
            paymentIntentMock.when(() -> PaymentIntent.retrieve(eq("pi_123"), any(RequestOptions.class)))
                .thenReturn(mockIntent);

            GatewayCaptureResult result = gateway.capture("pi_123", 2500L, "cap-idem-123");
            assertThat(result.success()).isTrue();

            ArgumentCaptor<RequestOptions> retrieveOptions = ArgumentCaptor.forClass(RequestOptions.class);
            paymentIntentMock.verify(() -> PaymentIntent.retrieve(eq("pi_123"), retrieveOptions.capture()));
            assertThat(retrieveOptions.getValue().getIdempotencyKey()).isNull();

            ArgumentCaptor<RequestOptions> captureOptions = ArgumentCaptor.forClass(RequestOptions.class);
            verify(mockIntent).capture(any(PaymentIntentCaptureParams.class), captureOptions.capture());
            assertThat(captureOptions.getValue().getIdempotencyKey()).isEqualTo("cap-idem-123");
        }
    }

    @Test
    @DisplayName("voidAuth passes null idempotencyKey to retrieve and mutating key to cancel")
    void voidAuthDoesNotReuseIdempotencyKeyOnRetrieve() throws Exception {
        StripePaymentGateway gateway = new StripePaymentGateway("sk_test_real_key", 5000, 15000, false);
        PaymentIntent mockIntent = mock(PaymentIntent.class);

        try (MockedStatic<PaymentIntent> paymentIntentMock = Mockito.mockStatic(PaymentIntent.class)) {
            paymentIntentMock.when(() -> PaymentIntent.retrieve(eq("pi_456"), any(RequestOptions.class)))
                .thenReturn(mockIntent);

            GatewayVoidResult result = gateway.voidAuth("pi_456", "void-idem-456");
            assertThat(result.success()).isTrue();

            ArgumentCaptor<RequestOptions> retrieveOptions = ArgumentCaptor.forClass(RequestOptions.class);
            paymentIntentMock.verify(() -> PaymentIntent.retrieve(eq("pi_456"), retrieveOptions.capture()));
            assertThat(retrieveOptions.getValue().getIdempotencyKey()).isNull();

            ArgumentCaptor<RequestOptions> cancelOptions = ArgumentCaptor.forClass(RequestOptions.class);
            verify(mockIntent).cancel(eq(Collections.emptyMap()), cancelOptions.capture());
            assertThat(cancelOptions.getValue().getIdempotencyKey()).isEqualTo("void-idem-456");
        }
    }

    @Test
    @DisplayName("local placeholder key uses local mock fallback only when explicitly enabled")
    void localPlaceholderFallbackBehavior() {
        StripePaymentGateway fallbackGateway = new StripePaymentGateway("sk_test_local_placeholder", 5000, 15000, true);
        GatewayAuthResult authResult = fallbackGateway.authorize(new GatewayAuthRequest(1500L, "INR", "idem-1", "pm_card_visa"));
        assertThat(authResult.success()).isTrue();
        assertThat(authResult.pspReference()).startsWith("mock_pi_");

        StripePaymentGateway unconfiguredGateway = new StripePaymentGateway("", 5000, 15000, false);
        assertThatThrownBy(() -> unconfiguredGateway.authorize(new GatewayAuthRequest(1500L, "INR", "idem-2", "pm_card_visa")))
            .isInstanceOf(PaymentGatewayException.class);
    }
}
