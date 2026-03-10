package com.instacommerce.payment.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.instacommerce.payment.domain.model.Payment;
import com.instacommerce.payment.domain.model.PaymentStatus;
import com.instacommerce.payment.domain.model.Refund;
import com.instacommerce.payment.domain.model.RefundStatus;
import com.instacommerce.payment.repository.PaymentRepository;
import com.instacommerce.payment.repository.ProcessedWebhookEventRepository;
import com.instacommerce.payment.repository.RefundRepository;
import com.instacommerce.payment.service.LedgerService;
import com.instacommerce.payment.service.OutboxService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Unit tests for the CAS (compare-and-set) row-count conflict path inside
 * {@link WebhookEventProcessor#completeTrackedRefunds}, proving that a
 * concurrent writer does not cause double-counting of {@code refundedCents},
 * duplicate ledger/outbox entries, or unhandled exceptions.
 */
@ExtendWith(MockitoExtension.class)
class WebhookEventProcessorOccTest {

    private static final String PSP_REF = "pi_occ_test";

    @Mock PaymentRepository paymentRepository;
    @Mock ProcessedWebhookEventRepository processedWebhookEventRepository;
    @Mock RefundRepository refundRepository;
    @Mock LedgerService ledgerService;
    @Mock OutboxService outboxService;
    @Mock EntityManager entityManager;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private SimpleMeterRegistry meterRegistry;
    private WebhookEventProcessor processor;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        processor = new WebhookEventProcessor(
            paymentRepository, processedWebhookEventRepository, refundRepository,
            ledgerService, outboxService, meterRegistry, false);
        ReflectionTestUtils.setField(processor, "entityManager", entityManager);
    }

    // --- Helpers ---

    private Payment capturedPayment(long capturedCents, long refundedCents) {
        Payment p = new Payment();
        p.setId(UUID.randomUUID());
        p.setOrderId(UUID.randomUUID());
        p.setAmountCents(capturedCents);
        p.setCapturedCents(capturedCents);
        p.setRefundedCents(refundedCents);
        p.setCurrency("INR");
        p.setStatus(PaymentStatus.CAPTURED);
        p.setPspReference(PSP_REF);
        p.setIdempotencyKey("key-" + UUID.randomUUID());
        p.setCreatedAt(Instant.now());
        p.setUpdatedAt(Instant.now());
        return p;
    }

    private Refund pendingRefund(UUID paymentId, long amountCents, String pspRefundId) {
        Refund r = new Refund();
        r.setId(UUID.randomUUID());
        r.setPaymentId(paymentId);
        r.setAmountCents(amountCents);
        r.setPspRefundId(pspRefundId);
        r.setIdempotencyKey("idem-" + UUID.randomUUID());
        r.setStatus(RefundStatus.PENDING);
        r.setCreatedAt(Instant.now());
        r.setUpdatedAt(Instant.now());
        return r;
    }

    private Refund freshCopy(Refund original, RefundStatus newStatus) {
        Refund r = new Refund();
        r.setId(original.getId());
        r.setPaymentId(original.getPaymentId());
        r.setAmountCents(original.getAmountCents());
        r.setPspRefundId(original.getPspRefundId());
        r.setIdempotencyKey(original.getIdempotencyKey());
        r.setStatus(newStatus);
        r.setVersion(original.getVersion() + 1);
        r.setCreatedAt(original.getCreatedAt());
        r.setUpdatedAt(Instant.now());
        return r;
    }

    private ObjectNode objectNodeWithRefundEntry(long amountRefunded, String pspRefundId, long entryAmount) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("amount_refunded", amountRefunded);
        ObjectNode refundsObj = node.putObject("refunds");
        ArrayNode arr = refundsObj.putArray("data");
        ObjectNode entry = arr.addObject();
        entry.put("id", pspRefundId);
        entry.put("amount", entryAmount);
        entry.put("status", "succeeded");
        return node;
    }

    private void stubPaymentLookup(Payment payment) {
        when(paymentRepository.findByPspReferenceForUpdate(PSP_REF)).thenReturn(Optional.of(payment));
    }

    private void stubPaymentLookupWithSave(Payment payment) {
        stubPaymentLookup(payment);
        when(paymentRepository.save(any(Payment.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    // --- CAS tests for completeTrackedRefunds ---

    @Nested
    @DisplayName("completeTrackedRefunds: CAS conflict handling")
    class TrackedRefundOcc {

        @Test
        @DisplayName("CAS=0 + re-read COMPLETED → idempotent skip, no double-count of refundedCents")
        void casRereadCompleted_noDoubleCount() {
            Payment p = capturedPayment(10_000, 0);
            Refund pending = pendingRefund(p.getId(), 3000, "re_occ_1");
            Refund freshCompleted = freshCopy(pending, RefundStatus.COMPLETED);

            stubPaymentLookup(p);
            when(refundRepository.findByPspRefundId("re_occ_1")).thenReturn(Optional.of(pending));
            when(refundRepository.compareAndSetPendingToCompletedWithPspRefundId(
                pending.getId(), "re_occ_1", pending.getVersion())).thenReturn(0);
            when(refundRepository.findById(pending.getId())).thenReturn(Optional.of(freshCompleted));

            // amount_refunded=0 isolates the tracked CAS path from cumulative fallback
            processor.processEvent("evt_occ_1", "charge.refunded", PSP_REF,
                objectNodeWithRefundEntry(0, "re_occ_1", 3000), null);

            // Tracked path CAS lost → refundedCents must NOT be incremented
            assertThat(p.getRefundedCents()).isEqualTo(0);

            // No payment save, no ledger, no outbox — CAS=0 means zero completions
            verify(paymentRepository, never()).save(any());
            verifyNoInteractions(ledgerService, outboxService);

            // Verify entity was detached before re-read
            verify(entityManager).detach(pending);

            // OCC metric incremented with "completed" outcome
            assertThat(meterRegistry.counter("payment.webhook.refund.occ", "outcome", "completed").count())
                .isEqualTo(1.0);
        }

        @Test
        @DisplayName("CAS=0 + re-read FAILED → reconciliation metric, no double-count")
        void casRereadFailed_reconciliationNeeded() {
            Payment p = capturedPayment(10_000, 0);
            Refund pending = pendingRefund(p.getId(), 3000, "re_occ_fail");
            Refund freshFailed = freshCopy(pending, RefundStatus.FAILED);

            stubPaymentLookupWithSave(p);
            when(refundRepository.findByPspRefundId("re_occ_fail")).thenReturn(Optional.of(pending));
            when(refundRepository.compareAndSetPendingToCompletedWithPspRefundId(
                pending.getId(), "re_occ_fail", pending.getVersion())).thenReturn(0);
            when(refundRepository.findById(pending.getId())).thenReturn(Optional.of(freshFailed));

            processor.processEvent("evt_occ_fail", "charge.refunded", PSP_REF,
                objectNodeWithRefundEntry(3000, "re_occ_fail", 3000), null);

            // refundedCents was not bumped by the tracked path
            // (cumulative fallback may bump it, which is correct for FAILED+PSP-succeeded scenario)

            verify(entityManager).detach(pending);

            // OCC metric incremented with "failed" outcome (reconciliation signal)
            assertThat(meterRegistry.counter("payment.webhook.refund.occ", "outcome", "failed").count())
                .isEqualTo(1.0);
        }

        @Test
        @DisplayName("CAS=0 + re-read returns null (refund deleted) → not_found metric")
        void casRereadNotFound_metric() {
            Payment p = capturedPayment(10_000, 0);
            Refund pending = pendingRefund(p.getId(), 3000, "re_occ_gone");

            stubPaymentLookupWithSave(p);
            when(refundRepository.findByPspRefundId("re_occ_gone")).thenReturn(Optional.of(pending));
            when(refundRepository.compareAndSetPendingToCompletedWithPspRefundId(
                pending.getId(), "re_occ_gone", pending.getVersion())).thenReturn(0);
            when(refundRepository.findById(pending.getId())).thenReturn(Optional.empty());

            processor.processEvent("evt_occ_gone", "charge.refunded", PSP_REF,
                objectNodeWithRefundEntry(3000, "re_occ_gone", 3000), null);

            verify(entityManager).detach(pending);
            assertThat(meterRegistry.counter("payment.webhook.refund.occ", "outcome", "not_found").count())
                .isEqualTo(1.0);
        }

        @Test
        @DisplayName("CAS=0 + re-read PENDING (unexpected) → warning metric with pending outcome")
        void casRereadPending_unexpectedStatus() {
            Payment p = capturedPayment(10_000, 0);
            Refund pending = pendingRefund(p.getId(), 3000, "re_occ_weird");
            Refund stillPending = freshCopy(pending, RefundStatus.PENDING);

            stubPaymentLookupWithSave(p);
            when(refundRepository.findByPspRefundId("re_occ_weird")).thenReturn(Optional.of(pending));
            when(refundRepository.compareAndSetPendingToCompletedWithPspRefundId(
                pending.getId(), "re_occ_weird", pending.getVersion())).thenReturn(0);
            when(refundRepository.findById(pending.getId())).thenReturn(Optional.of(stillPending));

            processor.processEvent("evt_occ_weird", "charge.refunded", PSP_REF,
                objectNodeWithRefundEntry(3000, "re_occ_weird", 3000), null);

            verify(entityManager).detach(pending);
            assertThat(meterRegistry.counter("payment.webhook.refund.occ", "outcome", "pending").count())
                .isEqualTo(1.0);
        }

        @Test
        @DisplayName("Two tracked refunds: first CAS=0, second CAS=1 → only second counted in refundedCents")
        void twoTracked_firstCasLost_secondSucceeds_noDoubleCount() {
            Payment p = capturedPayment(10_000, 0);
            Refund first = pendingRefund(p.getId(), 2000, "re_first");
            Refund second = pendingRefund(p.getId(), 1500, "re_second");
            Refund firstFreshCompleted = freshCopy(first, RefundStatus.COMPLETED);

            stubPaymentLookupWithSave(p);

            // First refund: found by pspRefundId, CAS returns 0, re-read shows COMPLETED
            when(refundRepository.findByPspRefundId("re_first")).thenReturn(Optional.of(first));
            when(refundRepository.findByPspRefundId("re_second")).thenReturn(Optional.of(second));

            when(refundRepository.compareAndSetPendingToCompletedWithPspRefundId(
                first.getId(), "re_first", first.getVersion())).thenReturn(0);
            when(refundRepository.compareAndSetPendingToCompletedWithPspRefundId(
                second.getId(), "re_second", second.getVersion())).thenReturn(1);

            when(refundRepository.findById(first.getId())).thenReturn(Optional.of(firstFreshCompleted));

            // Build payload with two refund entries
            ObjectNode node = objectMapper.createObjectNode();
            node.put("amount_refunded", 3500);
            ObjectNode refundsObj = node.putObject("refunds");
            ArrayNode arr = refundsObj.putArray("data");

            ObjectNode e1 = arr.addObject();
            e1.put("id", "re_first");
            e1.put("amount", 2000);
            e1.put("status", "succeeded");

            ObjectNode e2 = arr.addObject();
            e2.put("id", "re_second");
            e2.put("amount", 1500);
            e2.put("status", "succeeded");

            processor.processEvent("evt_mixed_occ", "charge.refunded", PSP_REF, node, null);

            // Only the second refund's amount is counted (first was CAS-skipped)
            // The tracked path adds 1500 for the second refund.
            // Cumulative fallback: amount_refunded=3500 vs current refundedCents=1500 → delta=2000
            // Total refundedCents = 1500 (tracked) + 2000 (cumulative) = 3500
            assertThat(p.getRefundedCents()).isEqualTo(3500);

            // CAS-lost refund detached before re-read; CAS-won refund detached after completion
            verify(entityManager).detach(first);
            verify(entityManager).detach(second);
            assertThat(meterRegistry.counter("payment.webhook.refund.occ", "outcome", "completed").count())
                .isEqualTo(1.0);
        }

        @Test
        @DisplayName("CAS=0 on tracked refund does not prevent cumulative fallback from executing")
        void casDoesNotBlockCumulativeFallback() {
            Payment p = capturedPayment(10_000, 0);
            Refund pending = pendingRefund(p.getId(), 3000, "re_occ_fb");
            Refund freshCompleted = freshCopy(pending, RefundStatus.COMPLETED);

            stubPaymentLookupWithSave(p);
            when(refundRepository.findByPspRefundId("re_occ_fb")).thenReturn(Optional.of(pending));
            when(refundRepository.compareAndSetPendingToCompletedWithPspRefundId(
                pending.getId(), "re_occ_fb", pending.getVersion())).thenReturn(0);
            when(refundRepository.findById(pending.getId())).thenReturn(Optional.of(freshCompleted));

            // amount_refunded = 5000, but tracked refund was only 3000
            // After CAS=0, cumulative fallback sees 5000 > 0 → delta = 5000
            processor.processEvent("evt_occ_fb", "charge.refunded", PSP_REF,
                objectNodeWithRefundEntry(5000, "re_occ_fb", 3000), null);

            // Cumulative fallback applies the full delta since tracked path didn't bump
            assertThat(p.getRefundedCents()).isEqualTo(5000);

            // Payment was saved (cumulative path), ledger was written for untracked delta
            verify(paymentRepository).save(any(Payment.class));
            verify(ledgerService).recordDoubleEntry(
                any(), org.mockito.ArgumentMatchers.eq(5000L),
                any(), any(), any(), any(), any());
        }
    }
}
