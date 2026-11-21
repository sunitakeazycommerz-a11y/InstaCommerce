package com.instacommerce.payment.service;

import com.instacommerce.payment.domain.model.Payment;
import com.instacommerce.payment.domain.model.PaymentStatus;
import com.instacommerce.payment.domain.model.Refund;
import com.instacommerce.payment.domain.model.RefundStatus;
import com.instacommerce.payment.dto.request.RefundRequest;
import com.instacommerce.payment.exception.PaymentInvalidStateException;
import com.instacommerce.payment.exception.PaymentNotFoundException;
import com.instacommerce.payment.exception.RefundExceedsChargeException;
import com.instacommerce.payment.repository.PaymentRepository;
import com.instacommerce.payment.repository.RefundRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.ConcurrentModificationException;
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
    private final PaymentOutboxFactory outboxFactory;
    private final AuditLogService auditLogService;

    @PersistenceContext
    private EntityManager entityManager;

    public RefundTransactionHelper(RefundRepository refundRepository,
                                   PaymentRepository paymentRepository,
                                   LedgerService ledgerService,
                                   OutboxService outboxService,
                                   AuditLogService auditLogService) {
        this.refundRepository = refundRepository;
        this.paymentRepository = paymentRepository;
        this.ledgerService = ledgerService;
        this.outboxFactory = new PaymentOutboxFactory(outboxService);
        this.auditLogService = auditLogService;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public RefundPendingResult savePendingRefund(UUID paymentId, RefundRequest request, String appliedKey) {
        if (request == null || request.amountCents() <= 0) {
            throw new IllegalArgumentException("Refund amountCents must be positive");
        }
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
        PaymentSupport.ensurePspReference(payment);

        Refund refund = new Refund();
        refund.setPaymentId(payment.getId());
        refund.setAmountCents(request.amountCents());
        refund.setReason(request.reason());
        refund.setIdempotencyKey(appliedKey);
        refund.setStatus(RefundStatus.PENDING);
        Refund saved = refundRepository.save(refund);

        return new RefundPendingResult(saved.getId(), payment.getPspReference());
    }

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
        long remainingRefundable = Math.max(0L, payment.getCapturedCents() - payment.getRefundedCents());
        if (request.amountCents() > remainingRefundable) {
            throw new RefundExceedsChargeException(paymentId, request.amountCents(), remainingRefundable);
        }

        int updated = refundRepository.compareAndSetPendingToCompletedWithPspRefundId(
            refundId, pspRefundId, refund.getVersion());
        if (updated == 0) {
            entityManager.detach(refund);
            Refund reloaded = refundRepository.findById(refundId).orElseThrow();
            if (reloaded.getStatus() == RefundStatus.COMPLETED) {
                log.info("Refund {} already completed by concurrent writer, returning idempotently", refundId);
                return reloaded;
            }
            throw new ConcurrentModificationException(
                "Refund " + refundId + " was concurrently modified to " + reloaded.getStatus());
        }

        entityManager.detach(refund);
        refund.setStatus(RefundStatus.COMPLETED);
        refund.setPspRefundId(pspRefundId);

        Map<String, Object> auditDetails = new LinkedHashMap<>();
        auditDetails.put("paymentId", payment.getId());
        auditDetails.put("amountCents", request.amountCents());
        if (request.reason() != null) {
            auditDetails.put("reason", request.reason());
        }
        applyRefundCompletion(payment, refund, request.amountCents(), "REFUND_ISSUED", auditDetails);
        return refund;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markRefundFailed(UUID refundId, String failureReason) {
        failPendingRefund(refundId, failureReason, "PSP refund call failed", "gateway", "REFUND_GATEWAY_FAILED", false);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void resolveStaleRefundFailed(UUID refundId, String reason) {
        failPendingRefund(
            refundId, reason, "Refund marked failed during recovery", "recovery", "RECOVERY_REFUND_FAILED", true);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Refund completeStaleRefund(UUID refundId) {
        Refund initial = refundRepository.findById(refundId).orElseThrow();
        UUID paymentId = initial.getPaymentId();

        Payment payment = paymentRepository.findByIdForUpdate(paymentId)
            .orElseThrow(() -> new PaymentNotFoundException(paymentId));

        entityManager.detach(initial);
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
        long remainingRefundable = Math.max(0L, payment.getCapturedCents() - payment.getRefundedCents());
        if (refund.getAmountCents() > remainingRefundable) {
            throw new RefundExceedsChargeException(paymentId, refund.getAmountCents(), remainingRefundable);
        }

        int updated = refundRepository.compareAndSetPendingToCompleted(refundId, refund.getVersion());
        if (updated == 0) {
            entityManager.detach(refund);
            Refund current = refundRepository.findById(refundId).orElse(null);
            if (current != null && current.getStatus() == RefundStatus.COMPLETED) {
                log.info("Refund {} already completed by concurrent writer during recovery, returning idempotently",
                    refundId);
                return current;
            }
            String currentStatus = current != null ? current.getStatus().name() : "UNREADABLE";
            log.error("Refund {} CAS lost during completeStaleRefund, current status={} requires manual review",
                refundId, currentStatus);
            throw new IllegalStateException(
                "Refund " + refundId + " CAS lost during completeStaleRefund, current status=" + currentStatus);
        }

        entityManager.detach(refund);
        refund.setStatus(RefundStatus.COMPLETED);

        applyRefundCompletion(payment, refund, refund.getAmountCents(), "RECOVERY_REFUND_COMPLETED",
            Map.of("paymentId", payment.getId(), "amountCents", refund.getAmountCents()));
        return refund;
    }

    private void applyRefundCompletion(Payment payment,
                                       Refund refund,
                                       long amountCents,
                                       String auditAction,
                                       Map<String, Object> auditDetails) {
        payment.setRefundedCents(payment.getRefundedCents() + amountCents);
        payment.setStatus(payment.getRefundedCents() >= payment.getCapturedCents()
            ? PaymentStatus.REFUNDED
            : PaymentStatus.PARTIALLY_REFUNDED);
        Payment savedPayment = paymentRepository.save(payment);

        ledgerService.recordDoubleEntry(savedPayment.getId(), amountCents,
            "merchant_payable", "customer_receivable", "REFUND", refund.getId().toString(), "Refund");
        outboxFactory.publishRefunded(savedPayment, refund.getId(), amountCents, refund.getCreatedAt(), refund.getReason());
        auditLogService.logSafely(null, auditAction, "Refund", refund.getId().toString(), auditDetails);
    }

    private void failPendingRefund(UUID refundId,
                                   String reason,
                                   String defaultReason,
                                   String failureSource,
                                   String auditAction,
                                   boolean includeReasonInAudit) {
        refundRepository.findById(refundId).ifPresent(r -> {
            if (r.getStatus() != RefundStatus.PENDING) {
                log.info("Refund {} no longer PENDING (status={}), skipping {}", refundId, r.getStatus(), auditAction);
                return;
            }
            int updated = refundRepository.compareAndSetPendingToFailed(refundId, r.getVersion());
            if (updated == 0) {
                log.info("Refund {} modified concurrently, skipping {} concurrent write wins", refundId, auditAction);
                return;
            }
            Payment payment = paymentRepository.findById(r.getPaymentId())
                .orElseThrow(() -> new PaymentNotFoundException(r.getPaymentId()));
            String normalizedReason = PaymentSupport.normalizeReason(reason, defaultReason);
            outboxFactory.publishRefundFailed(payment, r, normalizedReason, failureSource);
            Map<String, Object> details = includeReasonInAudit
                ? Map.of("paymentId", r.getPaymentId(), "reason", normalizedReason)
                : Map.of("paymentId", r.getPaymentId());
            auditLogService.logSafely(null, auditAction, "Refund", r.getId().toString(), details);
        });
    }

    public record RefundPendingResult(UUID refundId, String pspReference) {}
}
