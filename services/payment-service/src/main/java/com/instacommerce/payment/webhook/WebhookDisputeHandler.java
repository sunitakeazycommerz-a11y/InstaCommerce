package com.instacommerce.payment.webhook;

import com.fasterxml.jackson.databind.JsonNode;
import com.instacommerce.payment.domain.model.Payment;
import com.instacommerce.payment.domain.model.PaymentStatus;
import com.instacommerce.payment.repository.PaymentRepository;
import com.instacommerce.payment.service.AuditLogService;
import com.instacommerce.payment.service.LedgerService;
import com.instacommerce.payment.service.PaymentOutboxFactory;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class WebhookDisputeHandler {
    private static final Logger log = LoggerFactory.getLogger(WebhookDisputeHandler.class);

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

    private final PaymentRepository paymentRepository;
    private final LedgerService ledgerService;
    private final PaymentOutboxFactory outboxFactory;
    private final AuditLogService auditLogService;
    private final MeterRegistry meterRegistry;

    WebhookDisputeHandler(PaymentRepository paymentRepository,
                          LedgerService ledgerService,
                          PaymentOutboxFactory outboxFactory,
                          AuditLogService auditLogService,
                          MeterRegistry meterRegistry) {
        this.paymentRepository = paymentRepository;
        this.ledgerService = ledgerService;
        this.outboxFactory = outboxFactory;
        this.auditLogService = auditLogService;
        this.meterRegistry = meterRegistry;
    }

    void handleDisputeCreated(Payment payment, JsonNode objectNode, String eventId) {
        if (payment.getStatus() == PaymentStatus.DISPUTED) {
            log.debug("Webhook dispute created idempotent no-op: payment {} already DISPUTED", payment.getId());
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

        long disputeAmount = WebhookPayloadUtils.longValue(objectNode, "amount");
        if (disputeAmount <= 0) {
            disputeAmount = payment.getCapturedCents();
        }
        String reason = WebhookPayloadUtils.textValue(objectNode, "reason");

        payment.setStatus(PaymentStatus.DISPUTED);
        Payment saved = paymentRepository.save(payment);

        ledgerService.recordDoubleEntry(saved.getId(), disputeAmount,
            "merchant_payable", "dispute_hold", "DISPUTE",
            WebhookPayloadUtils.referenceId(eventId, saved), "Dispute created (webhook)");

        outboxFactory.publishDisputed(saved, disputeAmount, reason);

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

    void handleDisputeUpdated(Payment payment, JsonNode objectNode) {
        String status = WebhookPayloadUtils.textValue(objectNode, "status");
        String reason = WebhookPayloadUtils.textValue(objectNode, "reason");

        auditLogService.logSafely(null,
            "WEBHOOK_DISPUTE_UPDATED",
            "Payment",
            payment.getId().toString(),
            Map.of("orderId", payment.getOrderId(),
                "disputeStatus", status != null ? status : "",
                "reason", reason != null ? reason : "",
                "currency", payment.getCurrency()));

        outboxFactory.publishDisputeUpdated(payment, status, reason);

        log.info("Dispute update for payment {} (status={}, reason={})",
            payment.getId(), status, reason);
        meterRegistry.counter("payment.webhook.dispute_update", "outcome", "processed").increment();
    }

    void handleDisputeClosed(Payment payment, JsonNode objectNode, String eventId) {
        if (payment.getStatus() != PaymentStatus.DISPUTED) {
            log.debug("Webhook dispute closed idempotent no-op: payment {} not DISPUTED (status={})",
                payment.getId(), payment.getStatus());
            return;
        }

        String status = WebhookPayloadUtils.textValue(objectNode, "status");
        long amountCents = WebhookPayloadUtils.longValue(objectNode, "amount");
        if (amountCents <= 0) {
            amountCents = payment.getCapturedCents();
        }

        if ("won".equals(status)) {
            ledgerService.recordDoubleEntry(payment.getId(), amountCents,
                "dispute_hold", "merchant_payable", "DISPUTE_REVERSAL",
                WebhookPayloadUtils.referenceId(eventId, payment), "Dispute won — reversal (webhook)");

            if (payment.getRefundedCents() >= payment.getCapturedCents()) {
                payment.setStatus(PaymentStatus.REFUNDED);
            } else if (payment.getRefundedCents() > 0) {
                payment.setStatus(PaymentStatus.PARTIALLY_REFUNDED);
            } else {
                payment.setStatus(PaymentStatus.CAPTURED);
            }
            Payment saved = paymentRepository.save(payment);

            outboxFactory.publishDisputeClosed(saved, "PaymentDisputeWon", amountCents);
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
            return;
        }

        ledgerService.recordDoubleEntry(payment.getId(), amountCents,
            "dispute_hold", "customer_receivable", "DISPUTE_LOSS",
            WebhookPayloadUtils.referenceId(eventId, payment), "Dispute lost (webhook)");

        Payment saved = paymentRepository.save(payment);
        outboxFactory.publishDisputeClosed(saved, "PaymentDisputeLost", amountCents);
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
