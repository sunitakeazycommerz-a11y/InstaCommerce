package com.instacommerce.payment.webhook;

import com.fasterxml.jackson.databind.JsonNode;
import com.instacommerce.payment.domain.model.Payment;
import com.instacommerce.payment.domain.model.PaymentStatus;
import com.instacommerce.payment.domain.model.ProcessedWebhookEvent;
import com.instacommerce.payment.domain.model.Refund;
import com.instacommerce.payment.domain.model.RefundStatus;
import com.instacommerce.payment.repository.LedgerEntryRepository;
import com.instacommerce.payment.repository.PaymentRepository;
import com.instacommerce.payment.repository.ProcessedWebhookEventRepository;
import com.instacommerce.payment.repository.RefundRepository;
import com.instacommerce.payment.service.AuditLogService;
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
import java.util.Optional;
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
        PaymentStatus.FAILED,
        PaymentStatus.DISPUTED
    );

    static final Set<PaymentStatus> CAPTURABLE_STATES = Set.of(
        PaymentStatus.CAPTURE_PENDING,
        PaymentStatus.AUTHORIZED,
        PaymentStatus.AUTHORIZE_PENDING
    );

    static final Set<PaymentStatus> TERMINAL_NON_CAPTURABLE = Set.of(
        PaymentStatus.VOIDED,
        PaymentStatus.FAILED,
        PaymentStatus.REFUNDED,
        PaymentStatus.PARTIALLY_REFUNDED,
        PaymentStatus.DISPUTED
    );

    static final Set<PaymentStatus> VOIDABLE_STATES = Set.of(
        PaymentStatus.VOID_PENDING,
        PaymentStatus.AUTHORIZED,
        PaymentStatus.AUTHORIZE_PENDING
    );

    static final Set<PaymentStatus> TERMINAL_NON_VOIDABLE = Set.of(
        PaymentStatus.CAPTURED,
        PaymentStatus.FAILED,
        PaymentStatus.REFUNDED,
        PaymentStatus.PARTIALLY_REFUNDED,
        PaymentStatus.DISPUTED
    );

    static final Set<PaymentStatus> DISPUTABLE_STATES = Set.of(
        PaymentStatus.CAPTURED,
        PaymentStatus.PARTIALLY_REFUNDED
    );

    static final Set<PaymentStatus> TERMINAL_NON_DISPUTABLE = Set.of(
        PaymentStatus.VOIDED,
        PaymentStatus.FAILED,
        PaymentStatus.AUTHORIZE_PENDING,
        PaymentStatus.AUTHORIZED,
        PaymentStatus.CAPTURE_PENDING,
        PaymentStatus.VOID_PENDING
    );

    static final Set<PaymentStatus> FAILABLE_STATES = Set.of(
        PaymentStatus.AUTHORIZE_PENDING,
        PaymentStatus.AUTHORIZED,
        PaymentStatus.CAPTURE_PENDING,
        PaymentStatus.VOID_PENDING
    );

    static final Set<PaymentStatus> TERMINAL_NON_FAILABLE = Set.of(
        PaymentStatus.CAPTURED,
        PaymentStatus.VOIDED,
        PaymentStatus.REFUNDED,
        PaymentStatus.PARTIALLY_REFUNDED,
        PaymentStatus.DISPUTED
    );

    /** States where an authorization hold may already exist in ledger. */
    static final Set<PaymentStatus> POST_AUTHORIZATION_STATES = Set.of(
        PaymentStatus.AUTHORIZED,
        PaymentStatus.CAPTURE_PENDING,
        PaymentStatus.VOID_PENDING
    );

    private final PaymentRepository paymentRepository;
    private final ProcessedWebhookEventRepository processedWebhookEventRepository;
    private final RefundRepository refundRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final LedgerService ledgerService;
    private final OutboxService outboxService;
    private final AuditLogService auditLogService;
    private final MeterRegistry meterRegistry;
    private final boolean refundOutboxEnabled;
    private final boolean captureVoidOutboxEnabled;

    @PersistenceContext
    private EntityManager entityManager;

    public WebhookEventProcessor(PaymentRepository paymentRepository,
                                 ProcessedWebhookEventRepository processedWebhookEventRepository,
                                 RefundRepository refundRepository,
                                 LedgerEntryRepository ledgerEntryRepository,
                                 LedgerService ledgerService,
                                 OutboxService outboxService,
                                 AuditLogService auditLogService,
                                 MeterRegistry meterRegistry,
                                 @Value("${payment.webhook.refund-outbox-enabled:false}") boolean refundOutboxEnabled,
                                 @Value("${payment.webhook.capture-void-outbox-enabled:false}") boolean captureVoidOutboxEnabled) {
        this.paymentRepository = paymentRepository;
        this.processedWebhookEventRepository = processedWebhookEventRepository;
        this.refundRepository = refundRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
        this.ledgerService = ledgerService;
        this.outboxService = outboxService;
        this.auditLogService = auditLogService;
        this.meterRegistry = meterRegistry;
        this.refundOutboxEnabled = refundOutboxEnabled;
        this.captureVoidOutboxEnabled = captureVoidOutboxEnabled;
    }

    @Transactional
    public void processEvent(String eventId, String type, String pspReference,
                             JsonNode objectNode, String rawPayload) {
        if (eventId != null && !eventId.isBlank()) {
            try {
                ProcessedWebhookEvent processed = new ProcessedWebhookEvent();
                processed.setEventId(eventId);
                processed.setEventType(type);
                processed.setRawPayload(rawPayload);
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
            case "payment_intent.requires_capture" -> handleRequiresCapture(payment, eventId);
            case "payment_intent.canceled" -> handleVoided(payment, eventId);
            case "payment_intent.payment_failed" -> handleFailed(payment, objectNode, eventId);
            case "charge.refunded" -> handleRefunded(payment, objectNode, eventId);
            case "charge.refund.updated" -> handleRefundUpdated(payment, objectNode, eventId);
            case "charge.expired" -> handleExpired(payment, eventId);
            case "charge.dispute.created" -> handleDisputeCreated(payment, objectNode, eventId);
            case "charge.dispute.updated" -> handleDisputeUpdated(payment, objectNode, eventId);
            case "charge.dispute.closed" -> handleDisputeClosed(payment, objectNode, eventId);
            default -> log.debug("Ignoring webhook event {}", type);
        }
    }

    private void handleCaptured(Payment payment, JsonNode objectNode, String eventId) {
        if (payment.getStatus() == PaymentStatus.CAPTURED) {
            return;
        }
        if (TERMINAL_NON_CAPTURABLE.contains(payment.getStatus())) {
            log.warn("Webhook capture rejected: payment {} in terminal non-capturable state {}",
                payment.getId(), payment.getStatus());
            meterRegistry.counter("payment.webhook.capture", "outcome", "terminal_rejected").increment();
            return;
        }
        if (!CAPTURABLE_STATES.contains(payment.getStatus())) {
            meterRegistry.counter("payment.webhook.capture", "outcome", "transient_deferred").increment();
            throw new WebhookEventHandler.TransientWebhookStateException(payment.getId(), payment.getStatus());
        }

        boolean wasAuthorizePending = payment.getStatus() == PaymentStatus.AUTHORIZE_PENDING;

        long amount = longValue(objectNode, "amount_received", "amount");
        long capturedAmount = amount > 0 ? amount : payment.getAmountCents();
        long previousCaptured = payment.getCapturedCents();
        long delta = Math.max(0L, capturedAmount - previousCaptured);
        payment.setCapturedCents(Math.max(previousCaptured, capturedAmount));
        payment.setStatus(PaymentStatus.CAPTURED);
        Payment saved = paymentRepository.save(payment);

        // When jumping AUTHORIZE_PENDING → CAPTURED directly (auth webhook not yet
        // processed), record the authorization hold first so the capture and remainder
        // release ledger entries have something to draw from.
        if (wasAuthorizePending) {
            ledgerService.recordDoubleEntry(saved.getId(), saved.getAmountCents(),
                "customer_receivable", "authorization_hold", "AUTHORIZATION",
                saved.getId().toString(), "Authorization hold (webhook direct capture)");
            if (captureVoidOutboxEnabled) {
                publishAuthorizedOutbox(saved);
            }
            log.info("Direct capture for AUTHORIZE_PENDING payment {}, recorded authorization hold",
                saved.getId());
            meterRegistry.counter("payment.webhook.capture", "outcome", "direct_from_authorize_pending").increment();
        }

        if (delta > 0) {
            ledgerService.recordDoubleEntry(saved.getId(), delta,
                "authorization_hold", "merchant_payable", "CAPTURE",
                saved.getId().toString(), "Capture (webhook)");
        }
        // Release uncaptured authorization remainder for partial captures.
        // Uses PARTIAL_CAPTURE_RELEASE referenceType with payment ID so
        // LedgerService dedup prevents duplicates on webhook retries.
        long remainder = saved.getAmountCents() - capturedAmount;
        if (remainder > 0) {
            ledgerService.recordDoubleEntry(saved.getId(), remainder,
                "authorization_hold", "customer_receivable", "PARTIAL_CAPTURE_RELEASE",
                saved.getId().toString(), "Partial capture remainder release (webhook)");
            log.info("Released {} cents authorization remainder for partially captured payment {}",
                remainder, saved.getId());
            meterRegistry.counter("payment.webhook.capture", "outcome", "partial_capture_released").increment();
        }
        if (captureVoidOutboxEnabled) {
            publishCaptureOutbox(saved, capturedAmount);
            meterRegistry.counter("payment.webhook.capture", "outcome", "outbox_published").increment();
        }
        auditLogService.logSafely(null,
            "WEBHOOK_CAPTURED",
            "Payment",
            saved.getId().toString(),
            Map.of("orderId", saved.getOrderId(),
                "capturedCents", capturedAmount,
                "currency", saved.getCurrency()));
        meterRegistry.counter("payment.webhook.capture", "outcome", "processed").increment();
    }

    private void handleRequiresCapture(Payment payment, String eventId) {
        if (payment.getStatus() == PaymentStatus.AUTHORIZED
                || payment.getStatus() == PaymentStatus.CAPTURED) {
            log.debug("Webhook requires_capture idempotent no-op: payment {} already {}",
                payment.getId(), payment.getStatus());
            return;
        }
        if (TERMINAL_NON_CAPTURABLE.contains(payment.getStatus())) {
            log.debug("Webhook requires_capture ignored: payment {} in terminal state {}",
                payment.getId(), payment.getStatus());
            return;
        }
        if (payment.getStatus() != PaymentStatus.AUTHORIZE_PENDING) {
            log.debug("Webhook requires_capture ignored: payment {} in unexpected state {}",
                payment.getId(), payment.getStatus());
            return;
        }

        payment.setStatus(PaymentStatus.AUTHORIZED);
        Payment saved = paymentRepository.save(payment);

        ledgerService.recordDoubleEntry(saved.getId(), saved.getAmountCents(),
            "customer_receivable", "authorization_hold", "AUTHORIZATION",
            saved.getId().toString(), "Authorization hold (webhook)");

        if (captureVoidOutboxEnabled) {
            publishAuthorizedOutbox(saved);
            meterRegistry.counter("payment.webhook.requires_capture", "outcome", "outbox_published").increment();
        }

        auditLogService.logSafely(null,
            "WEBHOOK_AUTHORIZED",
            "Payment",
            saved.getId().toString(),
            Map.of("orderId", saved.getOrderId(),
                "amountCents", saved.getAmountCents(),
                "currency", saved.getCurrency()));

        log.info("Payment {} transitioned to AUTHORIZED via requires_capture webhook", saved.getId());
        meterRegistry.counter("payment.webhook.requires_capture", "outcome", "processed").increment();
    }

    private void handleVoided(Payment payment, String eventId) {
        if (payment.getStatus() == PaymentStatus.VOIDED) {
            return;
        }
        if (TERMINAL_NON_VOIDABLE.contains(payment.getStatus())) {
            log.warn("Webhook void rejected: payment {} in terminal non-voidable state {}",
                payment.getId(), payment.getStatus());
            meterRegistry.counter("payment.webhook.void", "outcome", "terminal_rejected").increment();
            return;
        }
        if (!VOIDABLE_STATES.contains(payment.getStatus())) {
            meterRegistry.counter("payment.webhook.void", "outcome", "transient_deferred").increment();
            throw new WebhookEventHandler.TransientWebhookStateException(payment.getId(), payment.getStatus());
        }

        boolean wasAuthorizePending = payment.getStatus() == PaymentStatus.AUTHORIZE_PENDING;
        payment.setStatus(PaymentStatus.VOIDED);
        Payment saved = paymentRepository.save(payment);
        // Only record void ledger release if an authorization hold exists.
        // AUTHORIZE_PENDING → VOIDED means the auth never completed, so no hold to release.
        if (!wasAuthorizePending || hasAuthorizationLedgerEntry(saved.getId())) {
            ledgerService.recordDoubleEntry(saved.getId(), saved.getAmountCents(),
                "authorization_hold", "customer_receivable", "VOID",
                saved.getId().toString(), "Authorization void (webhook)");
        }
        if (captureVoidOutboxEnabled) {
            publishVoidOutbox(saved);
            meterRegistry.counter("payment.webhook.void", "outcome", "outbox_published").increment();
        }
        auditLogService.logSafely(null,
            "WEBHOOK_VOIDED",
            "Payment",
            saved.getId().toString(),
            Map.of("orderId", saved.getOrderId(),
                "amountCents", saved.getAmountCents(),
                "currency", saved.getCurrency()));
        meterRegistry.counter("payment.webhook.void", "outcome", "processed").increment();
    }

    private void handleFailed(Payment payment, JsonNode objectNode, String eventId) {
        if (payment.getStatus() == PaymentStatus.FAILED) {
            return;
        }
        if (TERMINAL_NON_FAILABLE.contains(payment.getStatus())) {
            log.warn("Webhook fail rejected: payment {} in terminal non-failable state {}",
                payment.getId(), payment.getStatus());
            meterRegistry.counter("payment.webhook.fail", "outcome", "terminal_rejected").increment();
            return;
        }
        if (!FAILABLE_STATES.contains(payment.getStatus())) {
            meterRegistry.counter("payment.webhook.fail", "outcome", "transient_deferred").increment();
            throw new WebhookEventHandler.TransientWebhookStateException(payment.getId(), payment.getStatus());
        }

        PaymentStatus previousStatus = payment.getStatus();
        String reason = extractFailureReason(objectNode, previousStatus);
        boolean authReleased = false;

        payment.setStatus(PaymentStatus.FAILED);
        Payment saved = paymentRepository.save(payment);

        // Release authorization hold when failing from a post-authorization state,
        // but only if an AUTHORIZATION ledger entry actually exists for this payment.
        // Uses recordDoubleEntry dedup so webhook retries do not create duplicate releases.
        if (POST_AUTHORIZATION_STATES.contains(previousStatus)
                && hasAuthorizationLedgerEntry(saved.getId())) {
            ledgerService.recordDoubleEntry(saved.getId(), saved.getAmountCents(),
                "authorization_hold", "customer_receivable", "FAILURE_RELEASE",
                saved.getId().toString(), "Authorization release on failure (webhook)");
            authReleased = true;
            log.info("Released authorization hold for failed payment {} (previous status: {})",
                saved.getId(), previousStatus);
            meterRegistry.counter("payment.webhook.fail", "outcome", "auth_released").increment();
        }

        publishFailedOutbox(saved, reason);
        auditLogService.logSafely(null,
            "PAYMENT_FAILED",
            "Payment",
            saved.getId().toString(),
            Map.of("orderId", saved.getOrderId(),
                "previousStatus", previousStatus.name(),
                "reason", reason,
                "authReleased", authReleased));

        log.info("Payment {} failed via webhook: previousStatus={}, reason={}",
            saved.getId(), previousStatus, reason);
        meterRegistry.counter("payment.webhook.fail", "outcome", "processed").increment();
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

    private void handleRefundUpdated(Payment payment, JsonNode objectNode, String eventId) {
        String pspRefundId = textValue(objectNode, "id");
        if (pspRefundId == null || pspRefundId.isBlank()) {
            pspRefundId = textValue(objectNode.path("refund"), "id");
        }
        if (pspRefundId == null || pspRefundId.isBlank()) {
            log.warn("charge.refund.updated webhook missing refund ID, skipping");
            meterRegistry.counter("payment.webhook.refund_update", "outcome", "missing_id").increment();
            return;
        }

        String pspStatus = textValue(objectNode, "status");
        if (pspStatus == null || pspStatus.isBlank()) {
            pspStatus = textValue(objectNode.path("refund"), "status");
        }

        Optional<Refund> trackedOpt = refundRepository.findByPspRefundId(pspRefundId);
        if (trackedOpt.isEmpty()) {
            log.debug("charge.refund.updated for untracked pspRefundId={}, skipping", pspRefundId);
            meterRegistry.counter("payment.webhook.refund_update", "outcome", "untracked").increment();
            return;
        }

        Refund tracked = trackedOpt.get();

        if ("failed".equals(pspStatus) && tracked.getStatus() == RefundStatus.COMPLETED) {
            log.error("CRITICAL: Refund {} (pspRefundId={}) failed on PSP but locally COMPLETED — "
                + "requires immediate reconciliation. paymentId={}, amountCents={}",
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
            log.info("charge.refund.updated reports succeeded for PENDING refund {} — "
                + "charge.refunded webhook should complete it", tracked.getId());
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

    private void handleExpired(Payment payment, String eventId) {
        if (payment.getStatus() == PaymentStatus.VOIDED || payment.getStatus() == PaymentStatus.FAILED) {
            return;
        }
        if (payment.getStatus() == PaymentStatus.CAPTURED
                || payment.getStatus() == PaymentStatus.PARTIALLY_REFUNDED
                || payment.getStatus() == PaymentStatus.REFUNDED
                || payment.getStatus() == PaymentStatus.DISPUTED) {
            log.debug("Ignoring charge.expired for payment {} in post-capture state {}",
                payment.getId(), payment.getStatus());
            return;
        }

        boolean hadAuthHold = hasAuthorizationLedgerEntry(payment.getId());
        payment.setStatus(PaymentStatus.VOIDED);
        Payment saved = paymentRepository.save(payment);

        if (hadAuthHold) {
            ledgerService.recordDoubleEntry(saved.getId(), saved.getAmountCents(),
                "authorization_hold", "customer_receivable", "VOID",
                saved.getId().toString(), "Authorization expired (webhook)");
        }

        if (captureVoidOutboxEnabled) {
            publishVoidOutbox(saved);
        }

        auditLogService.logSafely(null,
            "WEBHOOK_EXPIRED",
            "Payment",
            saved.getId().toString(),
            Map.of("orderId", saved.getOrderId(),
                "amountCents", saved.getAmountCents(),
                "currency", saved.getCurrency(),
                "authHoldReleased", hadAuthHold));

        log.info("Payment {} expired via webhook, transitioned to VOIDED (authHoldReleased={})",
            saved.getId(), hadAuthHold);
        meterRegistry.counter("payment.webhook.expired", "outcome", "processed").increment();
    }

    private void handleDisputeCreated(Payment payment, JsonNode objectNode, String eventId) {
        if (payment.getStatus() == PaymentStatus.DISPUTED) {
            log.debug("Webhook dispute created idempotent no-op: payment {} already DISPUTED",
                payment.getId());
            return;
        }
        if (TERMINAL_NON_DISPUTABLE.contains(payment.getStatus())) {
            log.warn("Webhook dispute rejected: payment {} in terminal non-disputable state {}",
                payment.getId(), payment.getStatus());
            meterRegistry.counter("payment.webhook.dispute", "outcome", "terminal_rejected").increment();
            return;
        }
        if (!DISPUTABLE_STATES.contains(payment.getStatus())) {
            log.warn("Webhook dispute rejected: payment {} in unexpected state {}",
                payment.getId(), payment.getStatus());
            meterRegistry.counter("payment.webhook.dispute", "outcome", "unexpected_state").increment();
            return;
        }

        long disputeAmount = longValue(objectNode, "amount");
        if (disputeAmount <= 0) {
            disputeAmount = payment.getCapturedCents();
        }
        String reason = textValue(objectNode, "reason");

        payment.setStatus(PaymentStatus.DISPUTED);
        Payment saved = paymentRepository.save(payment);

        ledgerService.recordDoubleEntry(saved.getId(), disputeAmount,
            "merchant_payable", "dispute_hold", "DISPUTE",
            referenceId(eventId, saved), "Dispute created (webhook)");

        publishDisputeCreatedOutbox(saved, disputeAmount, reason);

        auditLogService.logSafely(null,
            "WEBHOOK_DISPUTE_CREATED",
            "Payment",
            saved.getId().toString(),
            Map.of("orderId", saved.getOrderId(),
                "disputeAmountCents", disputeAmount,
                "reason", reason != null ? reason : "",
                "currency", saved.getCurrency()));

        log.info("Payment {} transitioned to DISPUTED via webhook (amount={}, reason={})",
            saved.getId(), disputeAmount, reason);
        meterRegistry.counter("payment.webhook.dispute", "outcome", "processed").increment();
    }

    private void handleDisputeUpdated(Payment payment, JsonNode objectNode, String eventId) {
        String status = textValue(objectNode, "status");
        String reason = textValue(objectNode, "reason");

        auditLogService.logSafely(null,
            "WEBHOOK_DISPUTE_UPDATED",
            "Payment",
            payment.getId().toString(),
            Map.of("orderId", payment.getOrderId(),
                "disputeStatus", status != null ? status : "",
                "reason", reason != null ? reason : "",
                "currency", payment.getCurrency()));

        log.info("Dispute update for payment {} (status={}, reason={})",
            payment.getId(), status, reason);
        meterRegistry.counter("payment.webhook.dispute_update", "outcome", "processed").increment();
    }

    private void handleDisputeClosed(Payment payment, JsonNode objectNode, String eventId) {
        if (payment.getStatus() != PaymentStatus.DISPUTED) {
            log.debug("Webhook dispute closed idempotent no-op: payment {} not DISPUTED (status={})",
                payment.getId(), payment.getStatus());
            return;
        }

        String status = textValue(objectNode, "status");
        long amountCents = longValue(objectNode, "amount");
        if (amountCents <= 0) {
            amountCents = payment.getCapturedCents();
        }

        if ("won".equals(status)) {
            ledgerService.recordDoubleEntry(payment.getId(), amountCents,
                "dispute_hold", "merchant_payable", "DISPUTE_REVERSAL",
                referenceId(eventId, payment), "Dispute won — reversal (webhook)");

            if (payment.getRefundedCents() >= payment.getCapturedCents()) {
                payment.setStatus(PaymentStatus.REFUNDED);
            } else if (payment.getRefundedCents() > 0) {
                payment.setStatus(PaymentStatus.PARTIALLY_REFUNDED);
            } else {
                payment.setStatus(PaymentStatus.CAPTURED);
            }
            Payment saved = paymentRepository.save(payment);

            publishDisputeWonOutbox(saved, amountCents);

            auditLogService.logSafely(null,
                "WEBHOOK_DISPUTE_WON",
                "Payment",
                saved.getId().toString(),
                Map.of("orderId", saved.getOrderId(),
                    "amountCents", amountCents,
                    "revertedStatus", saved.getStatus().name(),
                    "currency", saved.getCurrency()));

            log.info("Dispute won for payment {}, reverted to {}", saved.getId(), saved.getStatus());
            meterRegistry.counter("payment.webhook.dispute_close", "outcome", "won").increment();
        } else {
            // Lost — dispute_hold money goes to customer
            ledgerService.recordDoubleEntry(payment.getId(), amountCents,
                "dispute_hold", "customer_receivable", "DISPUTE_LOSS",
                referenceId(eventId, payment), "Dispute lost (webhook)");

            // Keep status as DISPUTED — it is terminal for lost disputes
            Payment saved = paymentRepository.save(payment);

            publishDisputeLostOutbox(saved, amountCents);

            auditLogService.logSafely(null,
                "WEBHOOK_DISPUTE_LOST",
                "Payment",
                saved.getId().toString(),
                Map.of("orderId", saved.getOrderId(),
                    "amountCents", amountCents,
                    "currency", saved.getCurrency()));

            log.info("Dispute lost for payment {}, remains DISPUTED", saved.getId());
            meterRegistry.counter("payment.webhook.dispute_close", "outcome", "lost").increment();
        }
    }

    private void publishDisputeCreatedOutbox(Payment payment, long disputeAmountCents, String reason) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("orderId", payment.getOrderId());
        payload.put("paymentId", payment.getId());
        payload.put("disputeAmountCents", disputeAmountCents);
        payload.put("currency", payment.getCurrency());
        payload.put("reason", reason != null ? reason : "");
        outboxService.publish("Payment", payment.getId().toString(), "PaymentDisputed", payload);
    }

    private void publishDisputeWonOutbox(Payment payment, long amountCents) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("orderId", payment.getOrderId());
        payload.put("paymentId", payment.getId());
        payload.put("amountCents", amountCents);
        payload.put("currency", payment.getCurrency());
        outboxService.publish("Payment", payment.getId().toString(), "PaymentDisputeWon", payload);
    }

    private void publishDisputeLostOutbox(Payment payment, long amountCents) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("orderId", payment.getOrderId());
        payload.put("paymentId", payment.getId());
        payload.put("amountCents", amountCents);
        payload.put("currency", payment.getCurrency());
        outboxService.publish("Payment", payment.getId().toString(), "PaymentDisputeLost", payload);
    }

    private void publishCaptureOutbox(Payment payment, long capturedCents) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("orderId", payment.getOrderId());
        payload.put("paymentId", payment.getId());
        payload.put("amountCents", capturedCents);
        payload.put("currency", payment.getCurrency());
        outboxService.publish("Payment", payment.getId().toString(), "PaymentCaptured", payload);
    }

    private void publishAuthorizedOutbox(Payment payment) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("orderId", payment.getOrderId());
        payload.put("paymentId", payment.getId());
        payload.put("amountCents", payment.getAmountCents());
        payload.put("currency", payment.getCurrency());
        outboxService.publish("Payment", payment.getId().toString(), "PaymentAuthorized", payload);
    }

    private void publishVoidOutbox(Payment payment) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("orderId", payment.getOrderId());
        payload.put("paymentId", payment.getId());
        payload.put("amountCents", payment.getAmountCents());
        payload.put("currency", payment.getCurrency());
        payload.put("voidedAt", Instant.now().toString());
        outboxService.publish("Payment", payment.getId().toString(), "PaymentVoided", payload);
    }

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

    private void publishFailedOutbox(Payment payment, String reason) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("orderId", payment.getOrderId());
        payload.put("paymentId", payment.getId());
        payload.put("reason", reason);
        outboxService.publish("Payment", payment.getId().toString(), "PaymentFailed", payload);
    }

    private boolean hasAuthorizationLedgerEntry(UUID paymentId) {
        return ledgerEntryRepository.existsByPaymentIdAndReferenceTypeAndReferenceId(
            paymentId, "AUTHORIZATION", paymentId.toString());
    }

    private String extractFailureReason(JsonNode objectNode, PaymentStatus previousStatus) {
        if (objectNode != null) {
            String message = textValue(objectNode.path("last_payment_error"), "message");
            if (message != null && !message.isBlank()) {
                return message;
            }
            String code = textValue(objectNode.path("last_payment_error"), "code");
            if (code != null && !code.isBlank()) {
                return "PSP error: " + code;
            }
        }
        return "Payment failed during " + previousStatus.name();
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
