package com.instacommerce.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.instacommerce.payment.domain.model.Payment;
import com.instacommerce.payment.domain.model.PaymentStatus;
import com.instacommerce.payment.dto.request.AuthorizeRequest;
import com.instacommerce.payment.exception.PaymentDeclinedException;
import com.instacommerce.payment.gateway.GatewayAuthRequest;
import com.instacommerce.payment.gateway.GatewayAuthResult;
import com.instacommerce.payment.gateway.PaymentGateway;
import com.instacommerce.payment.repository.PaymentRepository;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Verifies that sync authorization failures (PSP exceptions and explicit declines)
 * publish a {@code PaymentFailed} outbox event and write an audit log — achieving
 * parity with the stale-recovery path ({@code resolveStaleAuthorizationFailed}).
 */
@ExtendWith(MockitoExtension.class)
class SyncAuthorizationFailureTest {

    // --- Helper-level tests (PaymentTransactionHelper.markAuthorizationFailed) ---

    @Mock PaymentRepository paymentRepository;
    @Mock LedgerService ledgerService;
    @Mock OutboxService outboxService;
    @Mock AuditLogService auditLogService;
    @Mock PaymentGateway paymentGateway;

    @Captor ArgumentCaptor<Map<String, Object>> outboxPayloadCaptor;
    @Captor ArgumentCaptor<Map<String, Object>> auditDetailsCaptor;

    private PaymentTransactionHelper helper;
    private PaymentService service;

    @BeforeEach
    void setUp() {
        helper = new PaymentTransactionHelper(
            paymentRepository, ledgerService, outboxService, auditLogService);
        // PaymentService depends on the helper; stub savePendingAuthorization later per test
        service = new PaymentService(paymentRepository, paymentGateway, helper);
    }

    private Payment authPendingPayment() {
        Payment p = new Payment();
        p.setId(UUID.randomUUID());
        p.setOrderId(UUID.randomUUID());
        p.setAmountCents(10_000);
        p.setCapturedCents(0);
        p.setRefundedCents(0);
        p.setCurrency("INR");
        p.setStatus(PaymentStatus.AUTHORIZE_PENDING);
        p.setIdempotencyKey("idem-key-" + UUID.randomUUID());
        p.setPaymentMethod("CARD");
        return p;
    }

    private void stubForUpdate(Payment payment) {
        when(paymentRepository.findByIdForUpdate(payment.getId()))
            .thenReturn(Optional.of(payment));
        when(paymentRepository.save(any(Payment.class)))
            .thenAnswer(inv -> inv.getArgument(0));
    }

    // =====================================================================
    // Helper-level: markAuthorizationFailed outbox + audit
    // =====================================================================

    @Nested
    @DisplayName("markAuthorizationFailed publishes PaymentFailed and writes audit")
    class HelperSideEffects {

        @Test
        @DisplayName("publishes PaymentFailed outbox event with correct payload")
        void publishesPaymentFailedOutboxEvent() {
            Payment payment = authPendingPayment();
            stubForUpdate(payment);

            helper.markAuthorizationFailed(payment.getId(), "Insufficient funds");

            verify(outboxService).publish(
                eq("Payment"),
                eq(payment.getId().toString()),
                eq("PaymentFailed"),
                outboxPayloadCaptor.capture());

            Map<String, Object> payload = outboxPayloadCaptor.getValue();
            assertThat(payload).containsEntry("orderId", payment.getOrderId());
            assertThat(payload).containsEntry("paymentId", payment.getId());
            assertThat(payload).containsEntry("reason", "Insufficient funds");
            assertThat(payload).doesNotContainKey("resolvedBy");
        }

        @Test
        @DisplayName("writes audit log with PAYMENT_AUTHORIZATION_FAILED action")
        void writesAuditLog() {
            Payment payment = authPendingPayment();
            stubForUpdate(payment);

            helper.markAuthorizationFailed(payment.getId(), "Card expired");

            verify(auditLogService).log(
                isNull(),
                eq("PAYMENT_AUTHORIZATION_FAILED"),
                eq("Payment"),
                eq(payment.getId().toString()),
                auditDetailsCaptor.capture());

            Map<String, Object> details = auditDetailsCaptor.getValue();
            assertThat(details).containsEntry("orderId", payment.getOrderId());
            assertThat(details).containsEntry("reason", "Card expired");
        }

        @Test
        @DisplayName("null reason falls back to safe default")
        void nullReasonFallback() {
            Payment payment = authPendingPayment();
            stubForUpdate(payment);

            helper.markAuthorizationFailed(payment.getId(), null);

            verify(outboxService).publish(
                anyString(), anyString(), eq("PaymentFailed"),
                outboxPayloadCaptor.capture());
            assertThat(outboxPayloadCaptor.getValue())
                .containsEntry("reason", "Authorization failed");
        }

        @Test
        @DisplayName("blank reason falls back to safe default")
        void blankReasonFallback() {
            Payment payment = authPendingPayment();
            stubForUpdate(payment);

            helper.markAuthorizationFailed(payment.getId(), "   ");

            verify(outboxService).publish(
                anyString(), anyString(), eq("PaymentFailed"),
                outboxPayloadCaptor.capture());
            assertThat(outboxPayloadCaptor.getValue())
                .containsEntry("reason", "Authorization failed");
        }

        @Test
        @DisplayName("non-existent payment is a silent no-op")
        void nonExistentPaymentNoOp() {
            UUID bogusId = UUID.randomUUID();
            when(paymentRepository.findByIdForUpdate(bogusId))
                .thenReturn(Optional.empty());

            helper.markAuthorizationFailed(bogusId, "some reason");

            verifyNoInteractions(outboxService);
            verifyNoInteractions(auditLogService);
        }

        @Test
        @DisplayName("already-FAILED payment does not re-publish or re-audit")
        void alreadyFailedIdempotent() {
            Payment payment = authPendingPayment();
            payment.setStatus(PaymentStatus.FAILED);
            when(paymentRepository.findByIdForUpdate(payment.getId()))
                .thenReturn(Optional.of(payment));

            helper.markAuthorizationFailed(payment.getId(), "duplicate attempt");

            verify(paymentRepository, never()).save(any());
            verifyNoInteractions(outboxService);
            verifyNoInteractions(auditLogService);
        }

        @Test
        @DisplayName("status transitions to FAILED")
        void statusTransition() {
            Payment payment = authPendingPayment();
            stubForUpdate(payment);

            helper.markAuthorizationFailed(payment.getId(), "PSP error");

            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
        }
    }

    // =====================================================================
    // Service-level: PaymentService.authorize threads reason correctly
    // =====================================================================

    @Nested
    @DisplayName("PaymentService.authorize threads failure reason into helper")
    class ServiceReasonThreading {

        /**
         * Wires mocks so savePendingAuthorization creates and persists a Payment,
         * and markAuthorizationFailed can look it up via findByIdForUpdate.
         */
        private AtomicReference<Payment> wireAuthorizeFlow(UUID orderId) {
            AtomicReference<Payment> saved = new AtomicReference<>();
            when(paymentRepository.findByIdempotencyKey(anyString()))
                .thenReturn(Optional.empty());
            when(paymentRepository.save(any(Payment.class)))
                .thenAnswer(inv -> {
                    Payment p = inv.getArgument(0);
                    if (p.getId() == null) {
                        p.setId(UUID.randomUUID());
                    }
                    saved.set(p);
                    return p;
                });
            when(paymentRepository.findByIdForUpdate(any(UUID.class)))
                .thenAnswer(inv -> Optional.ofNullable(saved.get()));
            return saved;
        }

        @Test
        @DisplayName("PSP exception threads exception message as reason")
        void pspExceptionThreadsReason() {
            UUID orderId = UUID.randomUUID();
            wireAuthorizeFlow(orderId);
            when(paymentGateway.authorize(any(GatewayAuthRequest.class)))
                .thenThrow(new RuntimeException("Connection timeout to PSP"));

            AuthorizeRequest request = new AuthorizeRequest(
                orderId, 10_000, "INR", "idem-key-1", "CARD");

            assertThatThrownBy(() -> service.authorize(request))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("Connection timeout to PSP");

            verify(outboxService).publish(
                anyString(), anyString(), eq("PaymentFailed"),
                outboxPayloadCaptor.capture());
            assertThat(outboxPayloadCaptor.getValue())
                .containsEntry("reason", "Connection timeout to PSP");
        }

        @Test
        @DisplayName("PSP decline threads declineReason as reason")
        void pspDeclineThreadsReason() {
            UUID orderId = UUID.randomUUID();
            wireAuthorizeFlow(orderId);
            when(paymentGateway.authorize(any(GatewayAuthRequest.class)))
                .thenReturn(GatewayAuthResult.declined("Insufficient funds"));

            AuthorizeRequest request = new AuthorizeRequest(
                orderId, 10_000, "INR", "idem-key-2", "CARD");

            assertThatThrownBy(() -> service.authorize(request))
                .isInstanceOf(PaymentDeclinedException.class);

            verify(outboxService).publish(
                anyString(), anyString(), eq("PaymentFailed"),
                outboxPayloadCaptor.capture());
            assertThat(outboxPayloadCaptor.getValue())
                .containsEntry("reason", "Insufficient funds");
        }

        @Test
        @DisplayName("PSP exception with null message uses safe fallback")
        void pspExceptionNullMessageFallback() {
            UUID orderId = UUID.randomUUID();
            wireAuthorizeFlow(orderId);
            when(paymentGateway.authorize(any(GatewayAuthRequest.class)))
                .thenThrow(new RuntimeException((String) null));

            AuthorizeRequest request = new AuthorizeRequest(
                orderId, 10_000, "INR", "idem-key-3", "CARD");

            assertThatThrownBy(() -> service.authorize(request))
                .isInstanceOf(RuntimeException.class);

            verify(outboxService).publish(
                anyString(), anyString(), eq("PaymentFailed"),
                outboxPayloadCaptor.capture());
            assertThat(outboxPayloadCaptor.getValue())
                .containsEntry("reason", "PSP authorization error");
        }

        @Test
        @DisplayName("PSP decline with null declineReason uses safe fallback")
        void pspDeclineNullReasonFallback() {
            UUID orderId = UUID.randomUUID();
            wireAuthorizeFlow(orderId);
            when(paymentGateway.authorize(any(GatewayAuthRequest.class)))
                .thenReturn(GatewayAuthResult.declined(null));

            AuthorizeRequest request = new AuthorizeRequest(
                orderId, 10_000, "INR", "idem-key-4", "CARD");

            assertThatThrownBy(() -> service.authorize(request))
                .isInstanceOf(PaymentDeclinedException.class);

            verify(outboxService).publish(
                anyString(), anyString(), eq("PaymentFailed"),
                outboxPayloadCaptor.capture());
            assertThat(outboxPayloadCaptor.getValue())
                .containsEntry("reason", "Authorization declined by PSP");
        }
    }
}
