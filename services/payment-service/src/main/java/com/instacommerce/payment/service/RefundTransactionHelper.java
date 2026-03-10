package com.instacommerce.payment.service;

import com.instacommerce.payment.domain.model.Payment;
import com.instacommerce.payment.domain.model.PaymentStatus;
import com.instacommerce.payment.domain.model.Refund;
import com.instacommerce.payment.domain.model.RefundStatus;
import com.instacommerce.payment.exception.PaymentGatewayException;
import com.instacommerce.payment.exception.PaymentInvalidStateException;
import com.instacommerce.payment.exception.PaymentNotFoundException;
import com.instacommerce.payment.exception.RefundExceedsChargeException;
import com.instacommerce.payment.dto.request.RefundRequest;
import com.instacommerce.payment.repository.PaymentRepository;
import com.instacommerce.payment.repository.RefundRepository;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
public class RefundTransactionHelper {
    private static final Logger log = LoggerFactory.getLogger(RefundTransactionHelper.class);

    private final RefundRepository refundRepository;
    private final PaymentRepository paymentRepository;
    private final LedgerService ledgerService;
    private final OutboxService outboxService;
    private final AuditLogService auditLogService;

    public RefundTransactionHelper(RefundRepository refundRepository,
                                   PaymentRepository paymentRepository,
                                   LedgerService ledgerService,
                                   OutboxService outboxService,
                                   AuditLogService auditLogService) {
        this.refundRepository = refundRepository;
        this.paymentRepository = paymentRepository;
        this.ledgerService = ledgerService;
        this.outboxService = outboxService;
        this.auditLogService = auditLogService;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public RefundPendingResult savePendingRefund(UUID paymentId, RefundRequest request, String appliedKey) {
        // Pessimistic lock to prevent double-refund race condition
        Payment payment = paymentRepository.findByIdForUpdate(paymentId)
            .orElseThrow(() -> new PaymentNotFoundException(paymentId));
        if (payment.getStatus() != PaymentStatus.CAPTURED
            && payment.getStatus() != PaymentStatus.PARTIALLY_REFUNDED) {
            throw new PaymentInvalidStateException(paymentId, payment.getStatus());
        }
        long pendingAmount = refundRepository.sumPendingAmountByPaymentId(paymentId);
        long available = payment.getCapturedCents() - payment.getRefundedCents() - pendingAmount;
        if (request.amountCents() > available) {
            throw new RefundExceedsChargeException(paymentId, request.amountCents(), available);
        }
        ensurePspReference(payment);

        Refund refund = new Refund();
        refund.setPaymentId(payment.getId());
        refund.setAmountCents(request.amountCents());
        refund.setReason(request.reason());
        refund.setIdempotencyKey(appliedKey);
        refund.setStatus(RefundStatus.PENDING);
        Refund saved = refundRepository.save(refund);

        return new RefundPendingResult(saved.getId(), payment.getPspReference());
    }

    /**
     * Persist the PSP refund ID on the pending row immediately after the gateway call
     * succeeds, so the webhook path can match it before synchronous completion runs.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void persistPspRefundId(UUID refundId, String pspRefundId) {
        refundRepository.setPspRefundIdIfMissing(refundId, pspRefundId);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Refund completeRefund(UUID refundId, UUID paymentId, RefundRequest request, String pspRefundId) {
        Payment payment = paymentRepository.findByIdForUpdate(paymentId)
            .orElseThrow(() -> new PaymentNotFoundException(paymentId));
        Refund refund = refundRepository.findById(refundId).orElseThrow();
        if (refund.getStatus() == RefundStatus.COMPLETED) {
            log.info("Refund {} already completed (likely by webhook), skipping synchronous completion", refundId);
            return refund;
        }
        refund.setStatus(RefundStatus.COMPLETED);
        refund.setPspRefundId(pspRefundId);
        Refund saved = refundRepository.save(refund);

        payment.setRefundedCents(payment.getRefundedCents() + request.amountCents());
        if (payment.getRefundedCents() >= payment.getCapturedCents()) {
            payment.setStatus(PaymentStatus.REFUNDED);
        } else {
            payment.setStatus(PaymentStatus.PARTIALLY_REFUNDED);
        }
        Payment savedPayment = paymentRepository.save(payment);

        // DEBIT merchant_payable, CREDIT customer_receivable (money flows back to customer)
        ledgerService.recordDoubleEntry(savedPayment.getId(), request.amountCents(),
            "merchant_payable", "customer_receivable", "REFUND", saved.getId().toString(), "Refund");

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("orderId", savedPayment.getOrderId());
        payload.put("paymentId", savedPayment.getId());
        payload.put("refundId", saved.getId());
        payload.put("amountCents", request.amountCents());
        payload.put("currency", savedPayment.getCurrency());
        payload.put("refundedAt", saved.getCreatedAt());
        if (saved.getReason() != null && !saved.getReason().isBlank()) {
            payload.put("reason", saved.getReason());
        }
        outboxService.publish("Payment", savedPayment.getId().toString(), "PaymentRefunded", payload);

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("paymentId", savedPayment.getId());
        details.put("amountCents", request.amountCents());
        if (request.reason() != null) {
            details.put("reason", request.reason());
        }
        auditLogService.log(null,
            "REFUND_ISSUED",
            "Refund",
            saved.getId().toString(),
            details);

        return saved;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markRefundFailed(UUID refundId) {
        refundRepository.findById(refundId).ifPresent(r -> {
            if (r.getStatus() != RefundStatus.PENDING) {
                log.info("Refund {} not PENDING (status={}), skipping markRefundFailed",
                    refundId, r.getStatus());
                return;
            }
            r.setStatus(RefundStatus.FAILED);
            refundRepository.save(r);
        });
    }

    /**
     * Recovery-path: mark a stale PENDING refund as FAILED with an audit reason.
     * Guards against overwriting terminal states in case a webhook resolved the
     * refund between the recovery query and this call.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void resolveStaleRefundFailed(UUID refundId, String reason) {
        refundRepository.findById(refundId).ifPresent(r -> {
            if (r.getStatus() != RefundStatus.PENDING) {
                log.info("Refund {} no longer PENDING (status={}), skipping recovery failure",
                    refundId, r.getStatus());
                return;
            }
            r.setStatus(RefundStatus.FAILED);
            refundRepository.save(r);

            auditLogService.log(null, "RECOVERY_REFUND_FAILED", "Refund", r.getId().toString(),
                Map.of("paymentId", r.getPaymentId(), "reason", reason));
        });
    }

    /**
     * Recovery-path: complete a stale PENDING refund that the PSP confirms as succeeded.
     * Mirrors {@link #completeRefund} but derives amount/reason from the persisted
     * refund row instead of requiring a {@code RefundRequest}.
     * Guards against overwriting terminal states.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Refund completeStaleRefund(UUID refundId) {
        // Read refund only to discover the paymentId; all decisions use the
        // re-read below so the status check is inside the payment lock.
        UUID paymentId = refundRepository.findById(refundId).orElseThrow().getPaymentId();

        // Acquire the payment lock first — same order as completeRefund() and
        // the webhook path — to prevent TOCTOU double-count of refundedCents.
        Payment payment = paymentRepository.findByIdForUpdate(paymentId)
            .orElseThrow(() -> new PaymentNotFoundException(paymentId));

        // Re-read refund after the lock so the status guard sees any concurrent
        // completion that committed while we were waiting on the lock.
        Refund refund = refundRepository.findById(refundId).orElseThrow();

        if (refund.getStatus() == RefundStatus.COMPLETED) {
            log.info("Refund {} already completed, skipping recovery completion", refundId);
            return refund;
        }
        if (refund.getStatus() != RefundStatus.PENDING) {
            log.info("Refund {} no longer PENDING (status={}), skipping recovery completion",
                refundId, refund.getStatus());
            return refund;
        }

        refund.setStatus(RefundStatus.COMPLETED);
        Refund saved = refundRepository.save(refund);

        payment.setRefundedCents(payment.getRefundedCents() + refund.getAmountCents());
        if (payment.getRefundedCents() >= payment.getCapturedCents()) {
            payment.setStatus(PaymentStatus.REFUNDED);
        } else {
            payment.setStatus(PaymentStatus.PARTIALLY_REFUNDED);
        }
        Payment savedPayment = paymentRepository.save(payment);

        ledgerService.recordDoubleEntry(savedPayment.getId(), refund.getAmountCents(),
            "merchant_payable", "customer_receivable", "REFUND", saved.getId().toString(), "Refund");

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("orderId", savedPayment.getOrderId());
        payload.put("paymentId", savedPayment.getId());
        payload.put("refundId", saved.getId());
        payload.put("amountCents", refund.getAmountCents());
        payload.put("currency", savedPayment.getCurrency());
        payload.put("refundedAt", saved.getCreatedAt());
        if (saved.getReason() != null && !saved.getReason().isBlank()) {
            payload.put("reason", saved.getReason());
        }
        outboxService.publish("Payment", savedPayment.getId().toString(), "PaymentRefunded", payload);

        auditLogService.log(null, "RECOVERY_REFUND_COMPLETED", "Refund", saved.getId().toString(),
            Map.of("paymentId", savedPayment.getId(), "amountCents", refund.getAmountCents()));

        return saved;
    }

    private void ensurePspReference(Payment payment) {
        if (payment.getPspReference() == null || payment.getPspReference().isBlank()) {
            throw new PaymentGatewayException("PSP reference is missing for payment " + payment.getId());
        }
    }

    public record RefundPendingResult(UUID refundId, String pspReference) {}
}
