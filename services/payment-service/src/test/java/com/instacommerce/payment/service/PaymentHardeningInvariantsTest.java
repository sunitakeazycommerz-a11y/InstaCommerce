package com.instacommerce.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.instacommerce.payment.domain.model.Payment;
import com.instacommerce.payment.domain.model.PaymentStatus;
import com.instacommerce.payment.domain.model.Refund;
import com.instacommerce.payment.domain.model.RefundStatus;
import com.instacommerce.payment.dto.request.AuthorizeRequest;
import com.instacommerce.payment.dto.request.RefundRequest;
import com.instacommerce.payment.dto.response.PaymentResponse;
import com.instacommerce.payment.exception.RefundExceedsChargeException;
import com.instacommerce.payment.gateway.GatewayStatusResult;
import com.instacommerce.payment.gateway.GatewayStatusResult.PspPaymentState;
import com.instacommerce.payment.gateway.PaymentGateway;
import com.instacommerce.payment.repository.LedgerEntryRepository;
import com.instacommerce.payment.repository.PaymentRepository;
import com.instacommerce.payment.repository.ProcessedWebhookEventRepository;
import com.instacommerce.payment.repository.RefundRepository;
import com.instacommerce.payment.service.StalePendingPaymentRecoveryJob.RecoveryOutcome;
import com.instacommerce.payment.webhook.WebhookEventProcessor;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.persistence.EntityManager;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class PaymentHardeningInvariantsTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void completeRefundAndCompleteStaleRefundRejectCumulativeOverflowAboveCapturedCents() {
        RefundRepository refundRepository = mock(RefundRepository.class);
        PaymentRepository paymentRepository = mock(PaymentRepository.class);
        LedgerService ledgerService = mock(LedgerService.class);
        OutboxService outboxService = mock(OutboxService.class);
        AuditLogService auditLogService = mock(AuditLogService.class);
        RefundTransactionHelper helper = new RefundTransactionHelper(
            refundRepository, paymentRepository, ledgerService, outboxService, auditLogService);
        ReflectionTestUtils.setField(helper, "entityManager", mock(EntityManager.class));

        UUID paymentId = UUID.randomUUID();
        UUID refundId = UUID.randomUUID();

        assertThatThrownBy(() -> helper.savePendingRefund(paymentId, new RefundRequest(0L, "bad", "k-0"), "k-0"))
            .isInstanceOf(IllegalArgumentException.class);

        Payment payment = new Payment();
        payment.setId(paymentId);
        payment.setStatus(PaymentStatus.PARTIALLY_REFUNDED);
        payment.setAmountCents(10_000L);
        payment.setCapturedCents(10_000L);
        payment.setRefundedCents(7_500L);

        Refund pendingRefund = new Refund();
        pendingRefund.setId(refundId);
        pendingRefund.setPaymentId(paymentId);
        pendingRefund.setAmountCents(3_000L);
        pendingRefund.setStatus(RefundStatus.PENDING);
        pendingRefund.setVersion(1L);

        when(paymentRepository.findByIdForUpdate(paymentId)).thenReturn(Optional.of(payment));
        when(refundRepository.findById(refundId)).thenReturn(Optional.of(pendingRefund));

        assertThatThrownBy(() -> helper.completeRefund(
            refundId, paymentId, new RefundRequest(3_000L, "partial", "rk-1"), "re_123"))
            .isInstanceOf(RefundExceedsChargeException.class);
        assertThat(payment.getRefundedCents()).isEqualTo(7_500L);
        verify(refundRepository, never()).compareAndSetPendingToCompletedWithPspRefundId(any(), any(), anyLong());

        assertThatThrownBy(() -> helper.completeStaleRefund(refundId))
            .isInstanceOf(RefundExceedsChargeException.class);
        assertThat(payment.getRefundedCents()).isEqualTo(7_500L);
        verify(refundRepository, never()).compareAndSetPendingToCompleted(any(), anyLong());
    }

    @Test
    void webhookPrechecksDuplicateEventAndClampsCumulativeRefundToCapturedAmount() {
        PaymentRepository paymentRepository = mock(PaymentRepository.class);
        ProcessedWebhookEventRepository processedRepo = mock(ProcessedWebhookEventRepository.class);
        RefundRepository refundRepository = mock(RefundRepository.class);
        LedgerEntryRepository ledgerEntryRepository = mock(LedgerEntryRepository.class);
        LedgerService ledgerService = mock(LedgerService.class);
        OutboxService outboxService = mock(OutboxService.class);
        AuditLogService auditLogService = mock(AuditLogService.class);

        WebhookEventProcessor processor = new WebhookEventProcessor(
            paymentRepository,
            processedRepo,
            refundRepository,
            ledgerEntryRepository,
            ledgerService,
            outboxService,
            auditLogService,
            new SimpleMeterRegistry(),
            true,
            true
        );

        when(processedRepo.existsById("evt_dup")).thenReturn(true);
        processor.processEvent("  evt_dup  ", "charge.refunded", "pi_1", objectMapper.createObjectNode(), "{}");
        verify(processedRepo, never()).saveAndFlush(any());
        verifyNoInteractions(paymentRepository, ledgerService, outboxService);

        Payment payment = new Payment();
        payment.setId(UUID.randomUUID());
        payment.setOrderId(UUID.randomUUID());
        payment.setPspReference("pi_cap");
        payment.setStatus(PaymentStatus.PARTIALLY_REFUNDED);
        payment.setAmountCents(10_000L);
        payment.setCapturedCents(10_000L);
        payment.setRefundedCents(6_000L);
        payment.setCurrency("INR");

        when(processedRepo.existsById("evt_over")).thenReturn(false);
        when(processedRepo.existsByExternalPaymentReferenceAndPspEventId("pi_cap", "evt_over")).thenReturn(false);
        when(paymentRepository.findByPspReferenceForUpdate("pi_cap")).thenReturn(Optional.of(payment));
        when(paymentRepository.save(any(Payment.class))).thenAnswer(inv -> inv.getArgument(0));

        ObjectNode payload = objectMapper.createObjectNode().put("amount_refunded", 15_000L);
        processor.processEvent("evt_over", "charge.refunded", "pi_cap", payload, "{}");

        assertThat(payment.getRefundedCents()).isEqualTo(10_000L);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        verify(ledgerService).recordDoubleEntry(
            eq(payment.getId()),
            eq(4_000L),
            eq("merchant_payable"),
            eq("customer_receivable"),
            eq("REFUND"),
            anyString(),
            eq("Refund (webhook)")
        );
    }

    @Test
    void staleAuthorizePendingSkipsUnknownPspStatusAndAuthorizeRetryReconcilesAuthorizedPsp() {
        PaymentRepository paymentRepository = mock(PaymentRepository.class);
        PaymentGateway paymentGateway = mock(PaymentGateway.class);
        PaymentTransactionHelper txHelper = mock(PaymentTransactionHelper.class);
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

        StalePendingPaymentRecoveryJob recoveryJob = new StalePendingPaymentRecoveryJob(
            paymentRepository, paymentGateway, txHelper, meterRegistry, 30, 50);

        Payment staleAuth = new Payment();
        staleAuth.setId(UUID.randomUUID());
        staleAuth.setStatus(PaymentStatus.AUTHORIZE_PENDING);
        staleAuth.setPspReference("pi_unknown_1");
        staleAuth.setAmountCents(5_000L);

        when(paymentGateway.getStatus("pi_unknown_1"))
            .thenReturn(new GatewayStatusResult(PspPaymentState.UNKNOWN, "mystery_state", null));

        RecoveryOutcome outcome = recoveryJob.recoverPayment(staleAuth);
        assertThat(outcome).isEqualTo(RecoveryOutcome.SKIPPED);
        verify(txHelper, never()).resolveStaleAuthorizationFailed(any(), anyString());

        PaymentService paymentService = new PaymentService(
            paymentRepository, paymentGateway, txHelper, meterRegistry);
        Payment pendingWithRef = new Payment();
        pendingWithRef.setId(UUID.randomUUID());
        pendingWithRef.setOrderId(UUID.randomUUID());
        pendingWithRef.setStatus(PaymentStatus.AUTHORIZE_PENDING);
        pendingWithRef.setPspReference("pi_auth_ok");
        pendingWithRef.setAmountCents(8_000L);
        pendingWithRef.setCurrency("INR");

        Payment completedAuth = new Payment();
        completedAuth.setId(pendingWithRef.getId());
        completedAuth.setOrderId(pendingWithRef.getOrderId());
        completedAuth.setStatus(PaymentStatus.AUTHORIZED);
        completedAuth.setPspReference("pi_auth_ok");
        completedAuth.setAmountCents(8_000L);
        completedAuth.setCurrency("INR");

        AuthorizeRequest request = new AuthorizeRequest(
            pendingWithRef.getOrderId(), 8_000L, "INR", "idem-retry-1", "CARD");
        when(txHelper.savePendingAuthorization(eq(request), eq("idem-retry-1"))).thenReturn(null);
        when(paymentRepository.findByIdempotencyKey("idem-retry-1")).thenReturn(Optional.of(pendingWithRef));
        when(paymentGateway.getStatus("pi_auth_ok"))
            .thenReturn(new GatewayStatusResult(PspPaymentState.REQUIRES_CAPTURE, "requires_capture", 0L));
        when(txHelper.completeAuthorization(pendingWithRef.getId(), "pi_auth_ok")).thenReturn(completedAuth);

        PaymentResponse response = paymentService.authorize(request);

        assertThat(response.status()).isEqualTo("AUTHORIZED");
        verify(txHelper).completeAuthorization(pendingWithRef.getId(), "pi_auth_ok");
    }
}
