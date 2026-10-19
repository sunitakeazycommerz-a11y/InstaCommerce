package com.instacommerce.payment.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.instacommerce.payment.domain.model.Payment;
import com.instacommerce.payment.domain.model.PaymentStatus;
import com.instacommerce.payment.dto.request.AuthorizeRequest;
import com.instacommerce.payment.exception.PaymentGatewayException;
import com.instacommerce.payment.exception.PaymentGatewayTransientException;
import com.instacommerce.payment.gateway.PaymentGateway;
import com.instacommerce.payment.repository.PaymentRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PaymentServiceTimeoutTest {

    @Mock
    private PaymentRepository paymentRepository;

    @Mock
    private PaymentGateway paymentGateway;

    @Mock
    private PaymentTransactionHelper txHelper;

    private PaymentService paymentService;
    private UUID paymentId;
    private Payment pendingPayment;

    @BeforeEach
    void setUp() {
        paymentService = new PaymentService(
            paymentRepository,
            paymentGateway,
            txHelper,
            new SimpleMeterRegistry()
        );
        paymentId = UUID.randomUUID();
        pendingPayment = new Payment();
        pendingPayment.setId(paymentId);
        pendingPayment.setOrderId(UUID.randomUUID());
        pendingPayment.setAmountCents(5000L);
        pendingPayment.setCurrency("INR");
        pendingPayment.setStatus(PaymentStatus.AUTHORIZE_PENDING);
        pendingPayment.setIdempotencyKey("idem-test-" + paymentId);
    }

    @Test
    @DisplayName("Transient timeout during authorize retains AUTHORIZE_PENDING and never marks failed")
    void authorizeTransientTimeoutRetainsPending() {
        AuthorizeRequest request = new AuthorizeRequest(
            pendingPayment.getOrderId(),
            5000L,
            "INR",
            "pm_card_visa",
            "idem-key-123"
        );

        when(txHelper.savePendingAuthorization(eq(request), anyString())).thenReturn(pendingPayment);
        when(paymentGateway.authorize(any())).thenThrow(
            new PaymentGatewayTransientException("Stripe API connection timeout", new RuntimeException())
        );

        assertThatThrownBy(() -> paymentService.authorize(request))
            .isInstanceOf(PaymentGatewayTransientException.class);

        // Verification of Issue #540 fix:
        // txHelper.markAuthorizationFailed must NEVER be called on transient PSP timeout!
        verify(txHelper, never()).markAuthorizationFailed(any(), any());
    }

    @Test
    @DisplayName("Definitive error during authorize marks authorization as failed")
    void authorizeDefinitiveErrorMarksFailed() {
        AuthorizeRequest request = new AuthorizeRequest(
            pendingPayment.getOrderId(),
            5000L,
            "INR",
            "pm_card_visa",
            "idem-key-456"
        );

        when(txHelper.savePendingAuthorization(eq(request), anyString())).thenReturn(pendingPayment);
        when(paymentGateway.authorize(any())).thenThrow(
            new PaymentGatewayException("Invalid API key or definitive merchant error")
        );

        assertThatThrownBy(() -> paymentService.authorize(request))
            .isInstanceOf(PaymentGatewayException.class);

        verify(txHelper).markAuthorizationFailed(eq(paymentId), anyString());
    }

    @Test
    @DisplayName("Transient timeout during capture retains CAPTURE_PENDING without reverting to authorized")
    void captureTransientTimeoutRetainsCapturePending() {
        pendingPayment.setStatus(PaymentStatus.CAPTURE_PENDING);
        pendingPayment.setPspReference("pi_stripe_123");
        when(txHelper.saveCapturePending(paymentId)).thenReturn(pendingPayment);
        when(paymentGateway.capture(eq("pi_stripe_123"), anyLong(), anyString())).thenThrow(
            new PaymentGatewayTransientException("Read timeout on capture", new RuntimeException())
        );

        assertThatThrownBy(() -> paymentService.capture(paymentId, 5000L, "cap-idem-1"))
            .isInstanceOf(PaymentGatewayTransientException.class);

        // Revert to authorized must NOT be called on transient timeout so recovery job can inspect PSP state
        verify(txHelper, never()).revertToAuthorized(any());
    }

    @Test
    @DisplayName("Transient timeout during void retains VOID_PENDING without reverting to authorized")
    void voidTransientTimeoutRetainsVoidPending() {
        pendingPayment.setStatus(PaymentStatus.VOID_PENDING);
        pendingPayment.setPspReference("pi_stripe_123");
        when(txHelper.saveVoidPending(paymentId)).thenReturn(pendingPayment);
        when(paymentGateway.voidAuth(eq("pi_stripe_123"), anyString())).thenThrow(
            new PaymentGatewayTransientException("Socket timeout on void", new RuntimeException())
        );

        assertThatThrownBy(() -> paymentService.voidAuth(paymentId, "void-idem-1"))
            .isInstanceOf(PaymentGatewayTransientException.class);

        // Revert void must NOT be called on transient timeout
        verify(txHelper, never()).revertVoidToAuthorized(any());
    }
}
