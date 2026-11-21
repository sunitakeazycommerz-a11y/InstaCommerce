package com.instacommerce.payment.webhook;

import com.fasterxml.jackson.databind.JsonNode;
import com.instacommerce.payment.domain.model.Payment;
import com.instacommerce.payment.domain.model.ProcessedWebhookEvent;
import com.instacommerce.payment.repository.LedgerEntryRepository;
import com.instacommerce.payment.repository.PaymentRepository;
import com.instacommerce.payment.repository.ProcessedWebhookEventRepository;
import com.instacommerce.payment.repository.RefundRepository;
import com.instacommerce.payment.service.AuditLogService;
import com.instacommerce.payment.service.LedgerService;
import com.instacommerce.payment.service.OutboxService;
import com.instacommerce.payment.service.PaymentOutboxFactory;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Transactional boundary for webhook event processing.
 */
@Component
public class WebhookEventProcessor {
    private static final Logger log = LoggerFactory.getLogger(WebhookEventProcessor.class);

    private final PaymentRepository paymentRepository;
    private final ProcessedWebhookEventRepository processedWebhookEventRepository;
    private final WebhookCaptureHandler captureHandler;
    private final WebhookRefundHandler refundHandler;
    private final WebhookDisputeHandler disputeHandler;

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
        PaymentOutboxFactory outboxFactory = new PaymentOutboxFactory(outboxService);
        this.captureHandler = new WebhookCaptureHandler(
            paymentRepository,
            ledgerEntryRepository,
            ledgerService,
            outboxFactory,
            auditLogService,
            meterRegistry,
            captureVoidOutboxEnabled);
        this.refundHandler = new WebhookRefundHandler(
            paymentRepository,
            refundRepository,
            ledgerService,
            outboxFactory,
            auditLogService,
            meterRegistry,
            refundOutboxEnabled,
            () -> entityManager);
        this.disputeHandler = new WebhookDisputeHandler(
            paymentRepository,
            ledgerService,
            outboxFactory,
            auditLogService,
            meterRegistry);
    }

    @Transactional
    public void processEvent(String eventId, String type, String pspReference,
                             JsonNode objectNode, String rawPayload) {
        String normalizedEventId = (eventId != null && !eventId.isBlank()) ? eventId.trim() : null;
        if (normalizedEventId != null) {
            if (processedWebhookEventRepository.existsById(normalizedEventId)
                || (pspReference != null && !pspReference.isBlank()
                    && processedWebhookEventRepository.existsByExternalPaymentReferenceAndPspEventId(
                        pspReference, normalizedEventId))) {
                log.debug("Webhook event {} already processed, skipping duplicate", normalizedEventId);
                return;
            }
            try {
                ProcessedWebhookEvent processed = new ProcessedWebhookEvent();
                processed.setEventId(normalizedEventId);
                processed.setPspEventId(normalizedEventId);
                processed.setExternalPaymentReference(pspReference);
                processed.setEventType(type);
                processed.setRawPayload(rawPayload);
                processedWebhookEventRepository.saveAndFlush(processed);
            } catch (DataIntegrityViolationException ex) {
                log.debug("Webhook event {} already processed (concurrent), skipping", normalizedEventId);
                return;
            }
        }

        // Acquire the payment row lock before tracked refunds so sync and webhook paths share lock order.
        paymentRepository.findByPspReferenceForUpdate(pspReference)
            .ifPresent(payment -> applyEvent(payment, type, objectNode, eventId));
    }

    private void applyEvent(Payment payment, String type, JsonNode objectNode, String eventId) {
        switch (type) {
            case "payment_intent.succeeded" -> captureHandler.handleCaptured(payment, objectNode);
            case "payment_intent.requires_capture" -> captureHandler.handleRequiresCapture(payment);
            case "payment_intent.canceled" -> captureHandler.handleVoided(payment);
            case "payment_intent.payment_failed" -> captureHandler.handleFailed(payment, objectNode);
            case "charge.refunded" -> refundHandler.handleRefunded(payment, objectNode, eventId);
            case "charge.refund.updated" -> refundHandler.handleRefundUpdated(objectNode);
            case "charge.expired" -> captureHandler.handleExpired(payment);
            case "charge.dispute.created" -> disputeHandler.handleDisputeCreated(payment, objectNode, eventId);
            case "charge.dispute.updated" -> disputeHandler.handleDisputeUpdated(payment, objectNode);
            case "charge.dispute.closed" -> disputeHandler.handleDisputeClosed(payment, objectNode, eventId);
            default -> log.debug("Ignoring webhook event {}", type);
        }
    }

    static UUID syntheticRefundId(String eventId) {
        return PaymentOutboxFactory.syntheticRefundId(eventId);
    }
}
