package com.instacommerce.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.instacommerce.payment.domain.model.Payment;
import com.instacommerce.payment.domain.model.PaymentStatus;
import com.instacommerce.payment.dto.request.AuthorizeRequest;
import com.instacommerce.payment.dto.request.RefundRequest;
import com.instacommerce.payment.dto.response.RefundResponse;
import com.instacommerce.payment.gateway.GatewayAuthRequest;
import com.instacommerce.payment.gateway.GatewayAuthResult;
import com.instacommerce.payment.gateway.GatewayRefundResult;
import com.instacommerce.payment.gateway.PaymentGateway;
import com.instacommerce.payment.repository.PaymentRepository;
import com.instacommerce.payment.repository.RefundRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Verifies that PaymentService and RefundService normalize oversized
 * idempotency keys before any repository lookup, entity storage, or
 * gateway call — preventing VARCHAR(64) overflows.
 */
@ExtendWith(MockitoExtension.class)
class IdempotencyKeyNormalizationTest {

    /** A key intentionally longer than the 64-char column limit. */
    private static final String LONG_RAW_KEY =
        "checkout-orch-session-abc123-order-def456-attempt-7-" + "x".repeat(50);

    static {
        // Sanity: ensure the test key is actually over the limit
        assert LONG_RAW_KEY.length() > 64 : "test key must exceed 64 chars";
    }

    private static final String NORMALIZED_LONG_KEY =
        IdempotencyKeys.normalize(LONG_RAW_KEY);

    @Nested
    @DisplayName("PaymentService.authorize with oversized key")
    class AuthorizeLongKey {

        @Mock private PaymentRepository paymentRepository;
        @Mock private PaymentGateway paymentGateway;
        @Mock private PaymentTransactionHelper txHelper;
        @InjectMocks private PaymentService paymentService;

        @Test
        @DisplayName("Stores normalized key and passes it to PSP gateway")
        void authorizeLongKeyNormalized() {
            UUID orderId = UUID.randomUUID();
            AuthorizeRequest request = new AuthorizeRequest(
                orderId, 5000L, "INR", LONG_RAW_KEY, "CARD");

            Payment pending = buildPayment(orderId, PaymentStatus.AUTHORIZE_PENDING);
            when(txHelper.savePendingAuthorization(eq(request), eq(NORMALIZED_LONG_KEY)))
                .thenReturn(pending);
            when(paymentGateway.authorize(any(GatewayAuthRequest.class)))
                .thenReturn(new GatewayAuthResult(true, "psp_ref_1", null));
            when(txHelper.completeAuthorization(pending.getId(), "psp_ref_1"))
                .thenReturn(pending);

            paymentService.authorize(request);

            // TX helper received the normalized (hashed) key
            verify(txHelper).savePendingAuthorization(eq(request), eq(NORMALIZED_LONG_KEY));

            // Gateway received the normalized key, not the raw oversized one
            ArgumentCaptor<GatewayAuthRequest> gw = ArgumentCaptor.forClass(GatewayAuthRequest.class);
            verify(paymentGateway).authorize(gw.capture());
            assertThat(gw.getValue().idempotencyKey()).isEqualTo(NORMALIZED_LONG_KEY);
            assertThat(gw.getValue().idempotencyKey()).hasSize(64);
        }

        @Test
        @DisplayName("Idempotent hit on long key uses normalized value for lookup")
        void idempotentHitLongKey() {
            UUID orderId = UUID.randomUUID();
            AuthorizeRequest request = new AuthorizeRequest(
                orderId, 5000L, "INR", LONG_RAW_KEY, "CARD");

            // savePendingAuthorization returns null → idempotent hit
            when(txHelper.savePendingAuthorization(eq(request), eq(NORMALIZED_LONG_KEY)))
                .thenReturn(null);

            Payment existing = buildPayment(orderId, PaymentStatus.AUTHORIZED);
            when(paymentRepository.findByIdempotencyKey(NORMALIZED_LONG_KEY))
                .thenReturn(Optional.of(existing));

            paymentService.authorize(request);

            // Lookup used the normalized key
            verify(paymentRepository).findByIdempotencyKey(NORMALIZED_LONG_KEY);
        }
    }

    @Nested
    @DisplayName("RefundService.refund with oversized key")
    class RefundLongKey {

        @Mock private RefundRepository refundRepository;
        @Mock private PaymentGateway paymentGateway;
        @Mock private RefundTransactionHelper txHelper;
        @InjectMocks private RefundService refundService;

        @Test
        @DisplayName("Looks up and stores normalized key for long refund idempotency key")
        void refundLongKeyNormalized() {
            UUID paymentId = UUID.randomUUID();
            RefundRequest request = new RefundRequest(3000L, "customer request", LONG_RAW_KEY);

            when(refundRepository.findByIdempotencyKey(NORMALIZED_LONG_KEY))
                .thenReturn(Optional.empty());
            when(txHelper.savePendingRefund(eq(paymentId), eq(request), eq(NORMALIZED_LONG_KEY)))
                .thenReturn(new RefundTransactionHelper.RefundPendingResult(
                    UUID.randomUUID(), "psp_ref_1"));
            when(paymentGateway.refund(eq("psp_ref_1"), eq(3000L), eq(NORMALIZED_LONG_KEY), any()))
                .thenReturn(new GatewayRefundResult(true, "refund_1", null));

            com.instacommerce.payment.domain.model.Refund refund =
                new com.instacommerce.payment.domain.model.Refund();
            refund.setId(UUID.randomUUID());
            refund.setStatus(com.instacommerce.payment.domain.model.RefundStatus.COMPLETED);
            refund.setAmountCents(3000L);
            when(txHelper.completeRefund(any(), eq(paymentId), eq(request), eq("refund_1")))
                .thenReturn(refund);

            refundService.refund(paymentId, request);

            // Repository lookup used normalized key
            verify(refundRepository).findByIdempotencyKey(NORMALIZED_LONG_KEY);
            // TX helper received normalized key for storage
            verify(txHelper).savePendingRefund(eq(paymentId), eq(request), eq(NORMALIZED_LONG_KEY));
            // Gateway received normalized key
            verify(paymentGateway).refund(eq("psp_ref_1"), eq(3000L), eq(NORMALIZED_LONG_KEY), any());
        }
    }

    @Nested
    @DisplayName("Short keys still pass through unchanged")
    class ShortKeyPassthrough {

        @Mock private PaymentRepository paymentRepository;
        @Mock private PaymentGateway paymentGateway;
        @Mock private PaymentTransactionHelper txHelper;
        @InjectMocks private PaymentService paymentService;

        @Test
        @DisplayName("UUID-length key is not hashed")
        void shortKeyNotHashed() {
            UUID orderId = UUID.randomUUID();
            String shortKey = UUID.randomUUID().toString();
            AuthorizeRequest request = new AuthorizeRequest(
                orderId, 1000L, "INR", shortKey, "CARD");

            Payment pending = buildPayment(orderId, PaymentStatus.AUTHORIZE_PENDING);
            when(txHelper.savePendingAuthorization(eq(request), eq(shortKey)))
                .thenReturn(pending);
            when(paymentGateway.authorize(any(GatewayAuthRequest.class)))
                .thenReturn(new GatewayAuthResult(true, "psp_ref_2", null));
            when(txHelper.completeAuthorization(pending.getId(), "psp_ref_2"))
                .thenReturn(pending);

            paymentService.authorize(request);

            verify(txHelper).savePendingAuthorization(eq(request), eq(shortKey));

            ArgumentCaptor<GatewayAuthRequest> gw = ArgumentCaptor.forClass(GatewayAuthRequest.class);
            verify(paymentGateway).authorize(gw.capture());
            assertThat(gw.getValue().idempotencyKey()).isEqualTo(shortKey);
        }
    }

    private static Payment buildPayment(UUID orderId, PaymentStatus status) {
        Payment payment = new Payment();
        payment.setId(UUID.randomUUID());
        payment.setOrderId(orderId);
        payment.setStatus(status);
        payment.setAmountCents(5000L);
        payment.setCapturedCents(0L);
        payment.setRefundedCents(0L);
        payment.setCurrency("INR");
        payment.setPspReference("psp_ref");
        return payment;
    }
}
