package com.instacommerce.payment.webhook;

import com.fasterxml.jackson.databind.JsonNode;
import com.instacommerce.payment.domain.model.Payment;
import com.instacommerce.payment.domain.model.PaymentStatus;
import com.instacommerce.payment.domain.model.Refund;
import com.instacommerce.payment.domain.model.RefundStatus;
import com.instacommerce.payment.repository.PaymentRepository;
import com.instacommerce.payment.repository.RefundRepository;
import com.instacommerce.payment.service.AuditLogService;
import com.instacommerce.payment.service.LedgerService;
import com.instacommerce.payment.service.PaymentOutboxFactory;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class WebhookRefundHandler {
    private static final Logger log = LoggerFactory.getLogger(WebhookRefundHandler.class);

    static final Set<PaymentStatus> REFUNDABLE_STATES = Set.of(
        PaymentStatus.CAPTURED,
        PaymentStatus.PARTIALLY_REFUNDED,
        PaymentStatus.REFUNDED
    );

    static final Set<PaymentStatus> TERMINAL_NON_REFUNDABLE = Set.of(
        PaymentStatus.VOIDED,
        PaymentStatus.FAILED,
        PaymentStatus.DISPUTED
    );

    private final PaymentRepository paymentRepository;
    private final RefundRepository refundRepository;
    private final LedgerService ledgerService;
    private final PaymentOutboxFactory outboxFactory;
    private final AuditLogService auditLogService;
    private final MeterRegistry meterRegistry;
    private final boolean refundOutboxEnabled;
    private final Supplier<EntityManager> entityManagerSupplier;

    WebhookRefundHandler(PaymentRepository paymentRepository,
                         RefundRepository refundRepository,
                         LedgerService ledgerService,
                         PaymentOutboxFactory outboxFactory,
                         AuditLogService auditLogService,
                         MeterRegistry meterRegistry,
                         boolean refundOutboxEnabled,
                         Supplier<EntityManager> entityManagerSupplier) {
        this.paymentRepository = paymentRepository;
        this.refundRepository = refundRepository;
        this.ledgerService = ledgerService;
        this.outboxFactory = outboxFactory;
        this.auditLogService = auditLogService;
        this.meterRegistry = meterRegistry;
        this.refundOutboxEnabled = refundOutboxEnabled;
        this.entityManagerSupplier = entityManagerSupplier;
    }

    void handleRefunded(Payment payment, JsonNode objectNode, String eventId) {
        if (TERMINAL_NON_REFUNDABLE.contains(payment.getStatus())) {
            log.warn("Webhook refund rejected: payment {} in terminal non-refundable state {}",
                payment.getId(), payment.getStatus());
            meterRegistry.counter("payment.webhook.refund", "outcome", "terminal_rejected").increment();
            return;
        }
        if (!REFUNDABLE_STATES.contains(payment.getStatus())) {
            meterRegistry.counter("payment.webhook.refund", "outcome", "transient_deferred").increment();
            throw new WebhookEventHandler.TransientWebhookStateException(payment.getId(), payment.getStatus());
        }

        List<TrackedRefundCompletion> trackedCompletions = completeTrackedRefunds(payment, objectNode);

        long cumulativeRefunded = WebhookPayloadUtils.longValue(objectNode, "amount_refunded");
        long untrackedDelta = 0;
        if (cumulativeRefunded > 0) {
            long cappedCumulative = Math.min(cumulativeRefunded, payment.getCapturedCents());
            if (cumulativeRefunded > payment.getCapturedCents()) {
                log.warn("Webhook cumulative refund {} exceeds captured amount {} for payment {}, clamping to capturedCents",
                    cumulativeRefunded, payment.getCapturedCents(), payment.getId());
            }
            long updatedRefunded = Math.max(payment.getRefundedCents(), cappedCumulative);
            untrackedDelta = updatedRefunded - payment.getRefundedCents();
            if (untrackedDelta > 0) {
                payment.setRefundedCents(updatedRefunded);
            }
        }

        if (trackedCompletions.isEmpty() && untrackedDelta == 0) {
            return;
        }

        payment.setStatus(payment.getRefundedCents() >= payment.getCapturedCents()
            ? PaymentStatus.REFUNDED
            : PaymentStatus.PARTIALLY_REFUNDED);
        Payment saved = paymentRepository.save(payment);

        for (TrackedRefundCompletion completion : trackedCompletions) {
            ledgerService.recordDoubleEntry(saved.getId(), completion.amountCents(),
                "merchant_payable", "customer_receivable", "REFUND",
                completion.refundId().toString(), "Refund (webhook)");
            if (refundOutboxEnabled) {
                outboxFactory.publishWebhookRefunded(
                    saved, completion.refundId().toString(), completion.amountCents(),
                    Instant.now(), completion.reason());
            }
        }

        if (untrackedDelta > 0) {
            ledgerService.recordDoubleEntry(saved.getId(), untrackedDelta,
                "merchant_payable", "customer_receivable", "REFUND",
                WebhookPayloadUtils.referenceId(eventId, saved), "Refund (webhook)");
            if (refundOutboxEnabled) {
                String syntheticId = PaymentOutboxFactory
                    .syntheticRefundId(eventId, saved.getId(), untrackedDelta)
                    .toString();
                outboxFactory.publishWebhookRefunded(saved, syntheticId, untrackedDelta, Instant.now(), "webhook");
            }
        }

        auditLogService.logSafely(null,
            "WEBHOOK_REFUNDED",
            "Payment",
            saved.getId().toString(),
            Map.of("orderId", saved.getOrderId(),
                "refundedCents", saved.getRefundedCents(),
                "status", saved.getStatus().name(),
                "currency", saved.getCurrency()));
        meterRegistry.counter("payment.webhook.refund", "outcome", "processed").increment();
        if (!trackedCompletions.isEmpty()) {
            meterRegistry.counter("payment.webhook.refund", "outcome", "tracked_completed")
                .increment(trackedCompletions.size());
        }
        if (refundOutboxEnabled && (!trackedCompletions.isEmpty() || untrackedDelta > 0)) {
            meterRegistry.counter("payment.webhook.refund", "outcome", "outbox_published").increment();
        }
    }

    void handleRefundUpdated(JsonNode objectNode) {
        String pspRefundId = WebhookPayloadUtils.textValue(objectNode, "id");
        if (pspRefundId == null || pspRefundId.isBlank()) {
            pspRefundId = WebhookPayloadUtils.textValue(objectNode.path("refund"), "id");
        }
        if (pspRefundId == null || pspRefundId.isBlank()) {
            log.warn("charge.refund.updated webhook missing refund ID, skipping");
            meterRegistry.counter("payment.webhook.refund_update", "outcome", "missing_id").increment();
            return;
        }

        String pspStatus = WebhookPayloadUtils.textValue(objectNode, "status");
        if (pspStatus == null || pspStatus.isBlank()) {
            pspStatus = WebhookPayloadUtils.textValue(objectNode.path("refund"), "status");
        }

        Optional<Refund> trackedOpt = refundRepository.findByPspRefundId(pspRefundId);
        if (trackedOpt.isEmpty()) {
            log.debug("charge.refund.updated for untracked pspRefundId={}, skipping", pspRefundId);
            meterRegistry.counter("payment.webhook.refund_update", "outcome", "untracked").increment();
            return;
        }

        Refund tracked = trackedOpt.get();
        if ("failed".equals(pspStatus) && tracked.getStatus() == RefundStatus.COMPLETED) {
            log.error("CRITICAL: Refund {} (pspRefundId={}) failed on PSP but locally COMPLETED, requires reconciliation. paymentId={}, amountCents={}",
                tracked.getId(), pspRefundId, tracked.getPaymentId(), tracked.getAmountCents());
            meterRegistry.counter("payment.webhook.refund_update", "outcome", "psp_reversal_detected").increment();
            auditLogService.logSafely(null,
                "WEBHOOK_REFUND_PSP_FAILURE",
                "Refund",
                tracked.getId().toString(),
                Map.of("paymentId", tracked.getPaymentId(),
                    "pspRefundId", pspRefundId,
                    "localStatus", tracked.getStatus().name(),
                    "pspStatus", pspStatus,
                    "amountCents", tracked.getAmountCents()));
            return;
        }

        if ("succeeded".equals(pspStatus) && tracked.getStatus() == RefundStatus.PENDING) {
            log.info("charge.refund.updated reports succeeded for PENDING refund {}, awaiting charge.refunded webhook",
                tracked.getId());
            meterRegistry.counter("payment.webhook.refund_update", "outcome", "pending_succeeded").increment();
        }

        auditLogService.logSafely(null,
            "WEBHOOK_REFUND_UPDATED",
            "Refund",
            tracked.getId().toString(),
            Map.of("paymentId", tracked.getPaymentId(),
                "pspRefundId", pspRefundId,
                "pspStatus", pspStatus != null ? pspStatus : "unknown",
                "localStatus", tracked.getStatus().name()));
        meterRegistry.counter("payment.webhook.refund_update", "outcome", "processed").increment();
    }

    private List<TrackedRefundCompletion> completeTrackedRefunds(Payment payment, JsonNode objectNode) {
        JsonNode refundsData = objectNode.path("refunds").path("data");
        if (!refundsData.isArray()) {
            return List.of();
        }

        List<TrackedRefundCompletion> completions = new ArrayList<>();
        for (JsonNode entry : refundsData) {
            String pspRefundId = WebhookPayloadUtils.textValue(entry, "id");
            if (pspRefundId == null || pspRefundId.isBlank()) {
                continue;
            }
            if (!"succeeded".equals(WebhookPayloadUtils.textValue(entry, "status"))) {
                continue;
            }

            Refund tracked = findTrackedRefund(entry, pspRefundId);
            if (tracked == null) {
                continue;
            }
            if (!payment.getId().equals(tracked.getPaymentId())) {
                log.warn("Tracked refund {} belongs to payment {} not {}, skipping",
                    tracked.getId(), tracked.getPaymentId(), payment.getId());
                continue;
            }
            if (tracked.getStatus() == RefundStatus.COMPLETED) {
                continue;
            }
            if (tracked.getStatus() != RefundStatus.PENDING) {
                log.warn("Tracked refund {} in unexpected state {} for pspRefundId {}, skipping",
                    tracked.getId(), tracked.getStatus(), pspRefundId);
                continue;
            }
            long remainingRefundable = Math.max(0L, payment.getCapturedCents() - payment.getRefundedCents());
            if (tracked.getAmountCents() > remainingRefundable) {
                log.warn("Tracked refund {} amount {} exceeds remaining refundable {} for payment {}, skipping",
                    tracked.getId(), tracked.getAmountCents(), remainingRefundable, payment.getId());
                continue;
            }

            int updated = refundRepository.compareAndSetPendingToCompletedWithPspRefundId(
                tracked.getId(), pspRefundId, tracked.getVersion());

            EntityManager em = entityManagerSupplier.get();
            if (updated == 0) {
                if (em != null) {
                    em.detach(tracked);
                }
                Refund fresh = refundRepository.findById(tracked.getId()).orElse(null);
                RefundStatus currentStatus = fresh != null ? fresh.getStatus() : null;
                if (currentStatus == RefundStatus.COMPLETED) {
                    log.info("Refund {} already completed by concurrent writer (pspRefundId={}), skipping idempotently",
                        tracked.getId(), pspRefundId);
                } else if (currentStatus == RefundStatus.FAILED) {
                    log.error("Refund {} CAS mismatch: PSP reports success while local status is FAILED (pspRefundId={})",
                        tracked.getId(), pspRefundId);
                } else {
                    log.warn("Refund {} CAS conflict: unexpected status {} after version conflict (pspRefundId={})",
                        tracked.getId(), currentStatus, pspRefundId);
                }
                meterRegistry.counter("payment.webhook.refund.occ",
                    "outcome", currentStatus != null ? currentStatus.name().toLowerCase() : "not_found").increment();
                continue;
            }

            payment.setRefundedCents(payment.getRefundedCents() + tracked.getAmountCents());
            completions.add(new TrackedRefundCompletion(tracked.getId(), tracked.getAmountCents(), tracked.getReason()));
            log.info("Webhook completed tracked refund {} (pspRefundId={}, amount={})",
                tracked.getId(), pspRefundId, tracked.getAmountCents());
            if (em != null) {
                em.detach(tracked);
            }
        }

        return completions;
    }

    private Refund findTrackedRefund(JsonNode entry, String pspRefundId) {
        Refund tracked = refundRepository.findByPspRefundId(pspRefundId).orElse(null);
        if (tracked != null) {
            return tracked;
        }
        String internalRefundId = WebhookPayloadUtils.textValue(entry.path("metadata"), "internalRefundId");
        if (internalRefundId == null || internalRefundId.isBlank()) {
            return null;
        }
        try {
            return refundRepository.findById(UUID.fromString(internalRefundId)).orElse(null);
        } catch (IllegalArgumentException ex) {
            log.warn("Webhook refund metadata has invalid internalRefundId {}", internalRefundId);
            return null;
        }
    }

    record TrackedRefundCompletion(UUID refundId, long amountCents, String reason) {
    }
}
