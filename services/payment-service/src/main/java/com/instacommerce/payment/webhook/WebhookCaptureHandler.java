package com.instacommerce.payment.webhook;

import com.fasterxml.jackson.databind.JsonNode;
import com.instacommerce.payment.domain.model.Payment;
import com.instacommerce.payment.domain.model.PaymentStatus;
import com.instacommerce.payment.repository.LedgerEntryRepository;
import com.instacommerce.payment.repository.PaymentRepository;
import com.instacommerce.payment.service.AuditLogService;
import com.instacommerce.payment.service.LedgerService;
import com.instacommerce.payment.service.PaymentOutboxFactory;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class WebhookCaptureHandler {
    private static final Logger log = LoggerFactory.getLogger(WebhookCaptureHandler.class);

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

    static final Set<PaymentStatus> POST_AUTHORIZATION_STATES = Set.of(
        PaymentStatus.AUTHORIZED,
        PaymentStatus.CAPTURE_PENDING,
        PaymentStatus.VOID_PENDING
    );

    private final PaymentRepository paymentRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final LedgerService ledgerService;
    private final PaymentOutboxFactory outboxFactory;
    private final AuditLogService auditLogService;
    private final MeterRegistry meterRegistry;
    private final boolean captureVoidOutboxEnabled;

    WebhookCaptureHandler(PaymentRepository paymentRepository,
                          LedgerEntryRepository ledgerEntryRepository,
                          LedgerService ledgerService,
                          PaymentOutboxFactory outboxFactory,
                          AuditLogService auditLogService,
                          MeterRegistry meterRegistry,
                          boolean captureVoidOutboxEnabled) {
        this.paymentRepository = paymentRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
        this.ledgerService = ledgerService;
        this.outboxFactory = outboxFactory;
        this.auditLogService = auditLogService;
        this.meterRegistry = meterRegistry;
        this.captureVoidOutboxEnabled = captureVoidOutboxEnabled;
    }

    void handleCaptured(Payment payment, JsonNode objectNode) {
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
        long amount = WebhookPayloadUtils.longValue(objectNode, "amount_received", "amount");
        long capturedAmount = amount > 0 ? amount : payment.getAmountCents();
        long previousCaptured = payment.getCapturedCents();
        long delta = Math.max(0L, capturedAmount - previousCaptured);
        payment.setCapturedCents(Math.max(previousCaptured, capturedAmount));
        payment.setStatus(PaymentStatus.CAPTURED);
        Payment saved = paymentRepository.save(payment);

        if (wasAuthorizePending) {
            ledgerService.recordDoubleEntry(saved.getId(), saved.getAmountCents(),
                "customer_receivable", "authorization_hold", "AUTHORIZATION",
                saved.getId().toString(), "Authorization hold (webhook direct capture)");
            if (captureVoidOutboxEnabled) {
                outboxFactory.publishAuthorized(saved);
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
            outboxFactory.publishCaptured(saved, capturedAmount);
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

    void handleRequiresCapture(Payment payment) {
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
            outboxFactory.publishAuthorized(saved);
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

    void handleVoided(Payment payment) {
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
        if (!wasAuthorizePending
            || WebhookPayloadUtils.hasAuthorizationLedgerEntry(ledgerEntryRepository, saved.getId())) {
            ledgerService.recordDoubleEntry(saved.getId(), saved.getAmountCents(),
                "authorization_hold", "customer_receivable", "VOID",
                saved.getId().toString(), "Authorization void (webhook)");
        }
        if (captureVoidOutboxEnabled) {
            outboxFactory.publishVoided(saved, Instant.now());
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

    void handleFailed(Payment payment, JsonNode objectNode) {
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
        String reason = WebhookPayloadUtils.extractFailureReason(objectNode, previousStatus);
        boolean authReleased = false;

        payment.setStatus(PaymentStatus.FAILED);
        Payment saved = paymentRepository.save(payment);

        if (POST_AUTHORIZATION_STATES.contains(previousStatus)
            && WebhookPayloadUtils.hasAuthorizationLedgerEntry(ledgerEntryRepository, saved.getId())) {
            ledgerService.recordDoubleEntry(saved.getId(), saved.getAmountCents(),
                "authorization_hold", "customer_receivable", "FAILURE_RELEASE",
                saved.getId().toString(), "Authorization release on failure (webhook)");
            authReleased = true;
            log.info("Released authorization hold for failed payment {} (previous status: {})",
                saved.getId(), previousStatus);
            meterRegistry.counter("payment.webhook.fail", "outcome", "auth_released").increment();
        }

        outboxFactory.publishFailed(saved, reason, null);
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

    void handleExpired(Payment payment) {
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

        boolean hadAuthHold = WebhookPayloadUtils.hasAuthorizationLedgerEntry(ledgerEntryRepository, payment.getId());
        payment.setStatus(PaymentStatus.VOIDED);
        Payment saved = paymentRepository.save(payment);

        if (hadAuthHold) {
            ledgerService.recordDoubleEntry(saved.getId(), saved.getAmountCents(),
                "authorization_hold", "customer_receivable", "VOID",
                saved.getId().toString(), "Authorization expired (webhook)");
        }

        if (captureVoidOutboxEnabled) {
            outboxFactory.publishVoided(saved, Instant.now());
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
}
