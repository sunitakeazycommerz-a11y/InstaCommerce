package com.instacommerce.payment.service;

import com.instacommerce.payment.domain.model.Payment;
import com.instacommerce.payment.domain.model.PaymentStatus;
import com.instacommerce.payment.dto.request.AuthorizeRequest;
import com.instacommerce.payment.exception.DuplicatePaymentException;
import com.instacommerce.payment.exception.PaymentInvalidStateException;
import com.instacommerce.payment.exception.PaymentNotFoundException;
import com.instacommerce.payment.repository.LedgerEntryRepository;
import com.instacommerce.payment.repository.PaymentRepository;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
public class PaymentTransactionHelper {
    private final PaymentRepository paymentRepository;
    private final LedgerService ledgerService;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final PaymentOutboxFactory outboxFactory;
    private final AuditLogService auditLogService;

    public PaymentTransactionHelper(PaymentRepository paymentRepository,
                                    LedgerService ledgerService,
                                    LedgerEntryRepository ledgerEntryRepository,
                                    OutboxService outboxService,
                                    AuditLogService auditLogService) {
        this.paymentRepository = paymentRepository;
        this.ledgerService = ledgerService;
        this.ledgerEntryRepository = ledgerEntryRepository;
        this.outboxFactory = new PaymentOutboxFactory(outboxService);
        this.auditLogService = auditLogService;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Payment savePendingAuthorization(AuthorizeRequest request, String normalizedKey) {
        Optional<Payment> existing = paymentRepository.findByIdempotencyKey(normalizedKey);
        if (existing.isPresent()) {
            Payment payment = existing.get();
            if ((request.orderId() != null && !payment.getOrderId().equals(request.orderId()))
                || payment.getAmountCents() != request.amountCents()) {
                throw new DuplicatePaymentException(request.idempotencyKey());
            }
            return null;
        }

        String currency = normalizeCurrency(request.currency());
        Payment payment = new Payment();
        UUID orderId = request.orderId() != null ? request.orderId() : UUID.randomUUID();
        payment.setOrderId(orderId);
        payment.setAmountCents(request.amountCents());
        payment.setCapturedCents(0);
        payment.setRefundedCents(0);
        payment.setCurrency(currency);
        payment.setStatus(PaymentStatus.AUTHORIZE_PENDING);
        payment.setIdempotencyKey(normalizedKey);
        payment.setPaymentMethod(request.effectivePaymentMethod());
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (request.userId() != null) {
            metadata.put("userId", request.userId().toString());
        }
        if (request.purpose() != null && !request.purpose().isBlank()) {
            metadata.put("purpose", request.purpose().trim().toUpperCase(Locale.ROOT));
        } else if (request.orderId() != null) {
            metadata.put("purpose", "ORDER_CHECKOUT");
        }
        if (!metadata.isEmpty()) {
            payment.setMetadata(metadata);
        }
        return paymentRepository.save(payment);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Payment completeAuthorization(UUID paymentId, String pspReference) {
        Payment payment = paymentRepository.findByIdForUpdate(paymentId)
            .orElseThrow(() -> new PaymentNotFoundException(paymentId));
        if (payment.getStatus() == PaymentStatus.AUTHORIZED) {
            return payment;
        }
        if (payment.getStatus() != PaymentStatus.AUTHORIZE_PENDING) {
            throw new PaymentInvalidStateException(paymentId, payment.getStatus());
        }
        payment.setStatus(PaymentStatus.AUTHORIZED);
        payment.setPspReference(pspReference);
        Payment saved = paymentRepository.save(payment);
        ledgerService.recordDoubleEntry(saved.getId(), saved.getAmountCents(),
            "customer_receivable", "authorization_hold", "AUTHORIZATION", saved.getId().toString(),
            "Authorization hold");
        outboxFactory.publishAuthorized(saved);
        auditLogService.logSafely(null,
            "PAYMENT_AUTHORIZED",
            "Payment",
            saved.getId().toString(),
            Map.of("orderId", saved.getOrderId(),
                "amountCents", saved.getAmountCents(),
                "currency", saved.getCurrency()));
        return saved;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markAuthorizationFailed(UUID paymentId, String reason) {
        failPendingAuthorization(paymentId, reason, null, "sync", "PAYMENT_AUTHORIZATION_FAILED");
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Payment saveCapturePending(UUID paymentId) {
        return transitionFromAuthorized(paymentId, PaymentStatus.CAPTURED, PaymentStatus.CAPTURE_PENDING);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Payment completeCaptured(UUID paymentId, long capturedCents) {
        Payment payment = paymentRepository.findByIdForUpdate(paymentId)
            .orElseThrow(() -> new PaymentNotFoundException(paymentId));
        if (payment.getStatus() == PaymentStatus.CAPTURED) {
            return payment;
        }
        if (payment.getStatus() != PaymentStatus.CAPTURE_PENDING) {
            throw new PaymentInvalidStateException(paymentId, payment.getStatus());
        }
        payment.setStatus(PaymentStatus.CAPTURED);
        payment.setCapturedCents(capturedCents);
        Payment saved = paymentRepository.save(payment);
        recordCaptureLedger(saved, capturedCents, "Capture", "Release uncaptured authorization remainder");
        outboxFactory.publishCaptured(saved, capturedCents);
        auditLogService.logSafely(null,
            "PAYMENT_CAPTURED",
            "Payment",
            saved.getId().toString(),
            Map.of("orderId", saved.getOrderId(),
                "amountCents", capturedCents,
                "currency", saved.getCurrency()));
        return saved;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void revertToAuthorized(UUID paymentId) {
        revertPendingToAuthorized(paymentId, PaymentStatus.CAPTURE_PENDING,
            "PaymentCaptureReverted", "PSP capture call failed", null, "CAPTURE_REVERTED");
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Payment saveVoidPending(UUID paymentId) {
        return transitionFromAuthorized(paymentId, PaymentStatus.VOIDED, PaymentStatus.VOID_PENDING);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Payment completeVoided(UUID paymentId) {
        Payment payment = paymentRepository.findByIdForUpdate(paymentId)
            .orElseThrow(() -> new PaymentNotFoundException(paymentId));
        if (payment.getStatus() == PaymentStatus.VOIDED) {
            return payment;
        }
        if (payment.getStatus() != PaymentStatus.VOID_PENDING) {
            throw new PaymentInvalidStateException(paymentId, payment.getStatus());
        }
        Instant voidedAt = Instant.now();
        payment.setStatus(PaymentStatus.VOIDED);
        Payment saved = paymentRepository.save(payment);
        ledgerService.recordDoubleEntry(saved.getId(), saved.getAmountCents(),
            "authorization_hold", "customer_receivable", "VOID", saved.getId().toString(), "Authorization void");
        outboxFactory.publishVoided(saved, voidedAt);
        auditLogService.logSafely(null,
            "PAYMENT_VOIDED",
            "Payment",
            saved.getId().toString(),
            Map.of("orderId", saved.getOrderId(),
                "amountCents", saved.getAmountCents(),
                "currency", saved.getCurrency(),
                "voidedAt", PaymentOutboxFactory.formatTimestamp(voidedAt)));
        return saved;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void revertVoidToAuthorized(UUID paymentId) {
        revertPendingToAuthorized(paymentId, PaymentStatus.VOID_PENDING,
            "PaymentVoidReverted", "PSP void call failed", null, "VOID_REVERTED");
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Payment reconcileDirectToCaptured(UUID paymentId, String pspReference, long capturedCents) {
        Payment payment = paymentRepository.findByIdForUpdate(paymentId)
            .orElseThrow(() -> new PaymentNotFoundException(paymentId));
        if (payment.getStatus() == PaymentStatus.CAPTURED) {
            return payment;
        }
        if (payment.getStatus() != PaymentStatus.AUTHORIZE_PENDING) {
            throw new PaymentInvalidStateException(paymentId, payment.getStatus());
        }
        payment.setPspReference(pspReference);
        payment.setStatus(PaymentStatus.CAPTURED);
        payment.setCapturedCents(capturedCents);
        Payment saved = paymentRepository.save(payment);
        ledgerService.recordDoubleEntry(saved.getId(), saved.getAmountCents(),
            "customer_receivable", "authorization_hold", "AUTHORIZATION", saved.getId().toString(),
            "Authorization hold (recovery reconciliation)");
        recordCaptureLedger(saved, capturedCents,
            "Capture (recovery reconciliation)",
            "Release uncaptured authorization remainder (recovery reconciliation)");
        outboxFactory.publishAuthorized(saved, "stale-pending-recovery");
        outboxFactory.publishCaptured(saved, capturedCents, "stale-pending-recovery");
        auditLogService.logSafely(null,
            "RECOVERY_RECONCILED_TO_CAPTURED",
            "Payment",
            saved.getId().toString(),
            Map.of("orderId", saved.getOrderId(),
                "amountCents", saved.getAmountCents(),
                "capturedCents", capturedCents,
                "currency", saved.getCurrency()));
        return saved;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void resolveStaleAuthorizationFailed(UUID paymentId, String reason) {
        failPendingAuthorization(paymentId, reason, "stale-pending-recovery", "recovery", "RECOVERY_AUTH_FAILED");
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void resolveStaleCaptureFailed(UUID paymentId, String reason) {
        revertPendingToAuthorized(paymentId, PaymentStatus.CAPTURE_PENDING,
            "PaymentCaptureReverted", reason, "stale-pending-recovery", "RECOVERY_CAPTURE_REVERTED");
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void resolveStaleVoidFailed(UUID paymentId, String reason) {
        revertPendingToAuthorized(paymentId, PaymentStatus.VOID_PENDING,
            "PaymentVoidReverted", reason, "stale-pending-recovery", "RECOVERY_VOID_REVERTED");
    }

    String normalizeCurrency(String currency) {
        return PaymentSupport.normalizeCurrency(currency);
    }

    private Payment transitionFromAuthorized(UUID paymentId, PaymentStatus idempotentStatus, PaymentStatus pendingStatus) {
        Payment payment = paymentRepository.findByIdForUpdate(paymentId)
            .orElseThrow(() -> new PaymentNotFoundException(paymentId));
        if (payment.getStatus() == idempotentStatus) {
            return payment;
        }
        if (payment.getStatus() != PaymentStatus.AUTHORIZED) {
            throw new PaymentInvalidStateException(paymentId, payment.getStatus());
        }
        PaymentSupport.ensurePspReference(payment);
        payment.setStatus(pendingStatus);
        return paymentRepository.save(payment);
    }

    private void recordCaptureLedger(Payment saved, long capturedCents, String captureDesc, String releaseDesc) {
        ledgerService.recordDoubleEntry(saved.getId(), capturedCents,
            "authorization_hold", "merchant_payable", "CAPTURE", saved.getId().toString(), captureDesc);
        long uncapturedCents = saved.getAmountCents() - capturedCents;
        if (uncapturedCents > 0) {
            ledgerService.recordDoubleEntry(saved.getId(), uncapturedCents,
                "authorization_hold", "customer_receivable",
                "PARTIAL_CAPTURE_RELEASE", saved.getId().toString(), releaseDesc);
        }
    }

    private void failPendingAuthorization(UUID paymentId, String reason, String resolvedBy,
                                          String releaseSource, String auditAction) {
        paymentRepository.findByIdForUpdate(paymentId).ifPresent(p -> {
            if (resolvedBy == null && p.getStatus() == PaymentStatus.FAILED) {
                return;
            }
            if (resolvedBy != null && p.getStatus() != PaymentStatus.AUTHORIZE_PENDING) {
                return;
            }
            String safeReason = PaymentSupport.normalizeReason(reason, "Authorization failed");
            p.setStatus(PaymentStatus.FAILED);
            paymentRepository.save(p);
            if (ledgerEntryRepository.existsByPaymentIdAndReferenceTypeAndReferenceId(
                    p.getId(), "AUTHORIZATION", p.getId().toString())) {
                ledgerService.recordDoubleEntry(p.getId(), p.getAmountCents(),
                    "authorization_hold", "customer_receivable", "FAILURE_RELEASE",
                    p.getId().toString(), "Authorization release on failure (" + releaseSource + ")");
            }
            outboxFactory.publishFailed(p, safeReason, resolvedBy);
            auditLogService.logSafely(null, auditAction, "Payment", p.getId().toString(),
                Map.of("orderId", p.getOrderId(), "reason", safeReason));
        });
    }

    private void revertPendingToAuthorized(UUID paymentId, PaymentStatus expectedPendingStatus,
                                           String outboxEvent, String reason,
                                           String resolvedBy, String auditAction) {
        paymentRepository.findByIdForUpdate(paymentId).ifPresent(p -> {
            if (p.getStatus() != expectedPendingStatus) {
                return;
            }
            p.setStatus(PaymentStatus.AUTHORIZED);
            paymentRepository.save(p);
            outboxFactory.publishReverted(p, outboxEvent, reason, resolvedBy);
            auditLogService.logSafely(null, auditAction, "Payment", p.getId().toString(),
                Map.of("orderId", p.getOrderId(), "reason", reason));
        });
    }
}
