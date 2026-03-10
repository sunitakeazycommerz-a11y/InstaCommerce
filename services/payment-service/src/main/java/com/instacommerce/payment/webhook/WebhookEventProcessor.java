package com.instacommerce.payment.webhook;

import com.fasterxml.jackson.databind.JsonNode;
import com.instacommerce.payment.domain.model.Payment;
import com.instacommerce.payment.domain.model.PaymentStatus;
import com.instacommerce.payment.domain.model.ProcessedWebhookEvent;
import com.instacommerce.payment.domain.model.Refund;
import com.instacommerce.payment.domain.model.RefundStatus;
import com.instacommerce.payment.repository.PaymentRepository;
import com.instacommerce.payment.repository.ProcessedWebhookEventRepository;
import com.instacommerce.payment.repository.RefundRepository;
import com.instacommerce.payment.service.LedgerService;
import com.instacommerce.payment.service.OutboxService;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Transactional boundary for webhook event processing.
 * <p>
 * Extracted from {@link WebhookEventHandler} so that Spring's proxy-based AOP
 * enforces the {@code @Transactional} annotation. When the handler called its
 * own {@code processEvent()} method directly (self-invocation), the proxy was
 * bypassed and no real transaction was created — breaking dedup-marker rollback
 * on transient deferrals and the {@code MANDATORY} propagation required by
 * {@link LedgerService} and {@link OutboxService}.
 */
@Component
public class WebhookEventProcessor {
    private static final Logger log = LoggerFactory.getLogger(WebhookEventProcessor.class);

    static final Set<PaymentStatus> REFUNDABLE_STATES = Set.of(
        PaymentStatus.CAPTURED,
        PaymentStatus.PARTIALLY_REFUNDED,
        PaymentStatus.REFUNDED
    );

    static final Set<PaymentStatus> TERMINAL_NON_REFUNDABLE = Set.of(
        PaymentStatus.VOIDED,
        PaymentStatus.FAILED
    );

    private final PaymentRepository paymentRepository;
    private final ProcessedWebhookEventRepository processedWebhookEventRepository;
    private final RefundRepository refundRepository;
    private final LedgerService ledgerService;
    private final OutboxService outboxService;
    private final MeterRegistry meterRegistry;
    private final boolean refundOutboxEnabled;

    @PersistenceContext
    private EntityManager entityManager;

    public WebhookEventProcessor(PaymentRepository paymentRepository,
                                 ProcessedWebhookEventRepository processedWebhookEventRepository,
                                 RefundRepository refundRepository,
                                 LedgerService ledgerService,
                                 OutboxService outboxService,
                                 MeterRegistry meterRegistry,
                                 @Value("${payment.webhook.refund-outbox-enabled:false}") boolean refundOutboxEnabled) {
        this.paymentRepository = paymentRepository;
        this.processedWebhookEventRepository = processedWebhookEventRepository;
        this.refundRepository = refundRepository;
        this.ledgerService = ledgerService;
        this.outboxService = outboxService;
        this.meterRegistry = meterRegistry;
        this.refundOutboxEnabled = refundOutboxEnabled;
    }

    @Transactional
    public void processEvent(String eventId, String type, String pspReference, JsonNode objectNode) {
        if (eventId != null && !eventId.isBlank()) {
            try {
                ProcessedWebhookEvent processed = new ProcessedWebhookEvent();
                processed.setEventId(eventId);
                processedWebhookEventRepository.saveAndFlush(processed);
            } catch (DataIntegrityViolationException ex) {
                log.debug("Webhook event {} already processed (concurrent), skipping", eventId);
                return;
            }
        }

        // Lock the payment row before touching tracked refund rows so webhook
        // processing uses the same payment -> refund lock order as sync completion.
        paymentRepository.findByPspReferenceForUpdate(pspReference)
            .ifPresent(payment -> applyEvent(payment, type, objectNode, eventId));
    }

    private void applyEvent(Payment payment, String type, JsonNode objectNode, String eventId) {
        switch (type) {
            case "payment_intent.succeeded" -> handleCaptured(payment, objectNode, eventId);
            case "payment_intent.canceled" -> handleVoided(payment, eventId);
            case "payment_intent.payment_failed" -> handleFailed(payment);
            case "charge.refunded" -> handleRefunded(payment, objectNode, eventId);
            default -> log.debug("Ignoring webhook event {}", type);
        }
    }

    private void handleCaptured(Payment payment, JsonNode objectNode, String eventId) {
        if (payment.getStatus() == PaymentStatus.CAPTURED) {
            return;
        }
        long amount = longValue(objectNode, "amount_received", "amount");
        long capturedAmount = amount > 0 ? amount : payment.getAmountCents();
        long previousCaptured = payment.getCapturedCents();
        long delta = Math.max(0L, capturedAmount - previousCaptured);
        payment.setCapturedCents(Math.max(previousCaptured, capturedAmount));
        payment.setStatus(PaymentStatus.CAPTURED);
        Payment saved = paymentRepository.save(payment);
        if (delta > 0) {
            ledgerService.recordDoubleEntry(saved.getId(), delta,
                "authorization_hold", "merchant_payable", "CAPTURE",
                referenceId(eventId, saved), "Capture (webhook)");
        }
    }

    private void handleVoided(Payment payment, String eventId) {
        if (payment.getStatus() == PaymentStatus.VOIDED) {
            return;
        }
        payment.setStatus(PaymentStatus.VOIDED);
        Payment saved = paymentRepository.save(payment);
        ledgerService.recordDoubleEntry(saved.getId(), saved.getAmountCents(),
            "authorization_hold", "customer_receivable", "VOID",
            referenceId(eventId, saved), "Authorization void (webhook)");
    }

    private void handleFailed(Payment payment) {
        if (payment.getStatus() == PaymentStatus.FAILED) {
            return;
        }
        payment.setStatus(PaymentStatus.FAILED);
        paymentRepository.save(payment);
    }

    private void handleRefunded(Payment payment, JsonNode objectNode, String eventId) {
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

        // Phase 1: Complete any tracked pending refunds matched by pspRefundId
        List<TrackedRefundCompletion> trackedCompletions = completeTrackedRefunds(payment, objectNode);

        // Phase 2: Cumulative fallback for untracked/manual refunds or payloads without per-refund entries
        long cumulativeRefunded = longValue(objectNode, "amount_refunded");
        long untrackedDelta = 0;
        if (cumulativeRefunded > 0) {
            long updatedRefunded = Math.max(payment.getRefundedCents(), cumulativeRefunded);
            untrackedDelta = updatedRefunded - payment.getRefundedCents();
            if (untrackedDelta > 0) {
                payment.setRefundedCents(updatedRefunded);
            }
        }

        if (trackedCompletions.isEmpty() && untrackedDelta == 0) {
            return;
        }

        // Update payment status and persist
        if (payment.getRefundedCents() >= payment.getCapturedCents()) {
            payment.setStatus(PaymentStatus.REFUNDED);
        } else {
            payment.setStatus(PaymentStatus.PARTIALLY_REFUNDED);
        }
        Payment saved = paymentRepository.save(payment);

        // Ledger + outbox for each tracked refund completion
        for (TrackedRefundCompletion completion : trackedCompletions) {
            ledgerService.recordDoubleEntry(saved.getId(), completion.amountCents(),
                "merchant_payable", "customer_receivable", "REFUND",
                completion.refundId().toString(), "Refund (webhook)");
            if (refundOutboxEnabled) {
                publishTrackedRefundOutbox(saved, completion);
            }
        }

        // Ledger + outbox for untracked cumulative delta (manual/dashboard refunds)
        if (untrackedDelta > 0) {
            ledgerService.recordDoubleEntry(saved.getId(), untrackedDelta,
                "merchant_payable", "customer_receivable", "REFUND",
                referenceId(eventId, saved), "Refund (webhook)");
            if (refundOutboxEnabled) {
                publishRefundOutbox(saved, untrackedDelta, eventId);
            }
        }

        meterRegistry.counter("payment.webhook.refund", "outcome", "processed").increment();
        if (!trackedCompletions.isEmpty()) {
            meterRegistry.counter("payment.webhook.refund", "outcome", "tracked_completed")
                .increment(trackedCompletions.size());
        }
        if (refundOutboxEnabled && (!trackedCompletions.isEmpty() || untrackedDelta > 0)) {
            meterRegistry.counter("payment.webhook.refund", "outcome", "outbox_published").increment();
        }
    }

    /**
     * Inspects per-refund entries from the Stripe {@code charge.refunded} payload
     * ({@code refunds.data[*]}) and completes any matching pending {@link Refund} rows.
     */
    private List<TrackedRefundCompletion> completeTrackedRefunds(Payment payment, JsonNode objectNode) {
        JsonNode refundsData = objectNode.path("refunds").path("data");
        if (!refundsData.isArray()) {
            return List.of();
        }

        List<TrackedRefundCompletion> completions = new ArrayList<>();
        for (JsonNode entry : refundsData) {
            String pspRefundId = textValue(entry, "id");
            if (pspRefundId == null || pspRefundId.isBlank()) {
                continue;
            }
            String status = textValue(entry, "status");
            if (!"succeeded".equals(status)) {
                continue;
            }

            Refund tracked = findTrackedRefund(entry, pspRefundId);
            if (tracked == null) {
                continue; // Untracked/manual refund — handled by cumulative fallback
            }
            if (!payment.getId().equals(tracked.getPaymentId())) {
                log.warn("Tracked refund {} belongs to payment {} not {}, skipping",
                    tracked.getId(), tracked.getPaymentId(), payment.getId());
                continue;
            }
            if (tracked.getStatus() == RefundStatus.COMPLETED) {
                continue; // Already completed by synchronous path
            }
            if (tracked.getStatus() != RefundStatus.PENDING) {
                log.warn("Tracked refund {} in unexpected state {} for pspRefundId {}, skipping",
                    tracked.getId(), tracked.getStatus(), pspRefundId);
                continue;
            }

            int updated = refundRepository.compareAndSetPendingToCompletedWithPspRefundId(
                tracked.getId(), pspRefundId, tracked.getVersion());

            if (updated == 0) {
                // CAS failed: another writer modified this refund concurrently.
                // Detach the stale entity so the re-read hits the database.
                entityManager.detach(tracked);
                Refund fresh = refundRepository.findById(tracked.getId()).orElse(null);
                RefundStatus currentStatus = fresh != null ? fresh.getStatus() : null;
                if (currentStatus == RefundStatus.COMPLETED) {
                    log.info("Refund {} already completed by concurrent writer (pspRefundId={}), skipping idempotently",
                        tracked.getId(), pspRefundId);
                } else if (currentStatus == RefundStatus.FAILED) {
                    log.error("Refund {} CAS: PSP says success but local status is FAILED — needs reconciliation attention (pspRefundId={})",
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

            completions.add(new TrackedRefundCompletion(
                tracked.getId(), tracked.getAmountCents(), tracked.getReason()));
            log.info("Webhook completed tracked refund {} (pspRefundId={}, amount={})",
                tracked.getId(), pspRefundId, tracked.getAmountCents());

            // The native CAS query bypassed JPA, so the managed entity still
            // holds stale PENDING state.  Detach it to avoid persistence-context
            // confusion for the remainder of this webhook transaction.
            entityManager.detach(tracked);
        }

        return completions;
    }

    private Refund findTrackedRefund(JsonNode entry, String pspRefundId) {
        Refund tracked = refundRepository.findByPspRefundId(pspRefundId).orElse(null);
        if (tracked != null) {
            return tracked;
        }

        String internalRefundId = textValue(entry.path("metadata"), "internalRefundId");
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

    record TrackedRefundCompletion(UUID refundId, long amountCents, String reason) {}

    private void publishTrackedRefundOutbox(Payment payment, TrackedRefundCompletion completion) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("refundId", completion.refundId().toString());
        payload.put("orderId", payment.getOrderId());
        payload.put("paymentId", payment.getId());
        payload.put("amountCents", completion.amountCents());
        payload.put("currency", payment.getCurrency());
        payload.put("refundedAt", Instant.now());
        String reason = completion.reason();
        payload.put("reason", (reason != null && !reason.isBlank()) ? reason : "webhook");
        outboxService.publish("Payment", payment.getId().toString(), "PaymentRefunded", payload);
    }

    private void publishRefundOutbox(Payment payment, long refundDelta, String eventId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("refundId", syntheticRefundId(eventId).toString());
        payload.put("orderId", payment.getOrderId());
        payload.put("paymentId", payment.getId());
        payload.put("amountCents", refundDelta);
        payload.put("currency", payment.getCurrency());
        payload.put("refundedAt", Instant.now());
        payload.put("reason", "webhook");
        outboxService.publish("Payment", payment.getId().toString(), "PaymentRefunded", payload);
    }

    static UUID syntheticRefundId(String eventId) {
        if (eventId != null && !eventId.isBlank()) {
            return UUID.nameUUIDFromBytes(("refund:" + eventId).getBytes(StandardCharsets.UTF_8));
        }
        return UUID.randomUUID();
    }

    private long longValue(JsonNode node, String... fields) {
        for (String field : fields) {
            JsonNode value = node.path(field);
            if (!value.isMissingNode() && value.isNumber()) {
                return value.asLong();
            }
        }
        return 0;
    }

    private String textValue(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        return value.asText();
    }

    private String referenceId(String eventId, Payment payment) {
        return (eventId == null || eventId.isBlank()) ? payment.getId().toString() : eventId;
    }
}
