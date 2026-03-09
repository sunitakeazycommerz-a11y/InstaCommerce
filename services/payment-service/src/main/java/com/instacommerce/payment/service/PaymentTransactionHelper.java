package com.instacommerce.payment.service;

import com.instacommerce.payment.domain.model.Payment;
import com.instacommerce.payment.domain.model.PaymentStatus;
import com.instacommerce.payment.dto.request.AuthorizeRequest;
import com.instacommerce.payment.exception.DuplicatePaymentException;
import com.instacommerce.payment.exception.PaymentGatewayException;
import com.instacommerce.payment.exception.PaymentInvalidStateException;
import com.instacommerce.payment.exception.PaymentNotFoundException;
import com.instacommerce.payment.repository.PaymentRepository;
import java.time.Instant;
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
    private final OutboxService outboxService;
    private final AuditLogService auditLogService;

    public PaymentTransactionHelper(PaymentRepository paymentRepository,
                                    LedgerService ledgerService,
                                    OutboxService outboxService,
                                    AuditLogService auditLogService) {
        this.paymentRepository = paymentRepository;
        this.ledgerService = ledgerService;
        this.outboxService = outboxService;
        this.auditLogService = auditLogService;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Payment savePendingAuthorization(AuthorizeRequest request, String normalizedKey) {
        Optional<Payment> existing = paymentRepository.findByIdempotencyKey(normalizedKey);
        if (existing.isPresent()) {
            Payment payment = existing.get();
            if (!payment.getOrderId().equals(request.orderId())
                || payment.getAmountCents() != request.amountCents()) {
                throw new DuplicatePaymentException(request.idempotencyKey());
            }
            return null; // idempotent return
        }

        String currency = normalizeCurrency(request.currency());
        Payment payment = new Payment();
        payment.setOrderId(request.orderId());
        payment.setAmountCents(request.amountCents());
        payment.setCapturedCents(0);
        payment.setRefundedCents(0);
        payment.setCurrency(currency);
        payment.setStatus(PaymentStatus.AUTHORIZE_PENDING);
        payment.setIdempotencyKey(normalizedKey);
        payment.setPaymentMethod(request.paymentMethod());
        return paymentRepository.save(payment);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Payment completeAuthorization(UUID paymentId, String pspReference) {
        Payment payment = paymentRepository.findById(paymentId)
            .orElseThrow(() -> new PaymentNotFoundException(paymentId));
        if (payment.getStatus() == PaymentStatus.AUTHORIZED) {
            return payment; // idempotent no-op
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
        outboxService.publish("Payment", saved.getId().toString(), "PaymentAuthorized",
            Map.of("orderId", saved.getOrderId(),
                "paymentId", saved.getId(),
                "amountCents", saved.getAmountCents(),
                "currency", saved.getCurrency()));
        auditLogService.log(null,
            "PAYMENT_AUTHORIZED",
            "Payment",
            saved.getId().toString(),
            Map.of("orderId", saved.getOrderId(),
                "amountCents", saved.getAmountCents(),
                "currency", saved.getCurrency()));
        return saved;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markAuthorizationFailed(UUID paymentId) {
        paymentRepository.findById(paymentId).ifPresent(p -> {
            p.setStatus(PaymentStatus.FAILED);
            paymentRepository.save(p);
        });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Payment saveCapturePending(UUID paymentId) {
        Payment payment = paymentRepository.findById(paymentId)
            .orElseThrow(() -> new PaymentNotFoundException(paymentId));
        if (payment.getStatus() == PaymentStatus.CAPTURED) {
            return payment;
        }
        if (payment.getStatus() != PaymentStatus.AUTHORIZED) {
            throw new PaymentInvalidStateException(paymentId, payment.getStatus());
        }
        ensurePspReference(payment);
        payment.setStatus(PaymentStatus.CAPTURE_PENDING);
        return paymentRepository.save(payment);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Payment completeCaptured(UUID paymentId, long capturedCents) {
        Payment payment = paymentRepository.findById(paymentId)
            .orElseThrow(() -> new PaymentNotFoundException(paymentId));
        if (payment.getStatus() == PaymentStatus.CAPTURED) {
            return payment; // idempotent no-op
        }
        if (payment.getStatus() != PaymentStatus.CAPTURE_PENDING) {
            throw new PaymentInvalidStateException(paymentId, payment.getStatus());
        }
        payment.setStatus(PaymentStatus.CAPTURED);
        payment.setCapturedCents(capturedCents);
        Payment saved = paymentRepository.save(payment);
        ledgerService.recordDoubleEntry(saved.getId(), capturedCents,
            "authorization_hold", "merchant_payable", "CAPTURE", saved.getId().toString(), "Capture");
        outboxService.publish("Payment", saved.getId().toString(), "PaymentCaptured",
            Map.of("orderId", saved.getOrderId(),
                "paymentId", saved.getId(),
                "amountCents", capturedCents,
                "currency", saved.getCurrency()));
        auditLogService.log(null,
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
        paymentRepository.findById(paymentId).ifPresent(p -> {
            if (p.getStatus() == PaymentStatus.CAPTURE_PENDING) {
                p.setStatus(PaymentStatus.AUTHORIZED);
                paymentRepository.save(p);
            }
        });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Payment saveVoidPending(UUID paymentId) {
        Payment payment = paymentRepository.findById(paymentId)
            .orElseThrow(() -> new PaymentNotFoundException(paymentId));
        if (payment.getStatus() == PaymentStatus.VOIDED) {
            return payment;
        }
        if (payment.getStatus() != PaymentStatus.AUTHORIZED) {
            throw new PaymentInvalidStateException(paymentId, payment.getStatus());
        }
        ensurePspReference(payment);
        payment.setStatus(PaymentStatus.VOID_PENDING);
        return paymentRepository.save(payment);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Payment completeVoided(UUID paymentId) {
        Payment payment = paymentRepository.findById(paymentId)
            .orElseThrow(() -> new PaymentNotFoundException(paymentId));
        if (payment.getStatus() == PaymentStatus.VOIDED) {
            return payment; // idempotent no-op
        }
        if (payment.getStatus() != PaymentStatus.VOID_PENDING) {
            throw new PaymentInvalidStateException(paymentId, payment.getStatus());
        }
        Instant voidedAt = Instant.now();
        payment.setStatus(PaymentStatus.VOIDED);
        Payment saved = paymentRepository.save(payment);
        ledgerService.recordDoubleEntry(saved.getId(), saved.getAmountCents(),
            "authorization_hold", "customer_receivable", "VOID", saved.getId().toString(), "Authorization void");
        outboxService.publish("Payment", saved.getId().toString(), "PaymentVoided",
            Map.of("orderId", saved.getOrderId(),
                "paymentId", saved.getId(),
                "amountCents", saved.getAmountCents(),
                "currency", saved.getCurrency(),
                "voidedAt", voidedAt.toString()));
        auditLogService.log(null,
            "PAYMENT_VOIDED",
            "Payment",
            saved.getId().toString(),
            Map.of("orderId", saved.getOrderId(),
                "amountCents", saved.getAmountCents(),
                "currency", saved.getCurrency(),
                "voidedAt", voidedAt.toString()));
        return saved;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void revertVoidToAuthorized(UUID paymentId) {
        paymentRepository.findById(paymentId).ifPresent(p -> {
            if (p.getStatus() == PaymentStatus.VOID_PENDING) {
                p.setStatus(PaymentStatus.AUTHORIZED);
                paymentRepository.save(p);
            }
        });
    }

    /**
     * Single-transaction reconciliation for AUTHORIZE_PENDING payments that the PSP
     * already captured. Transitions directly to CAPTURED with combined side effects,
     * avoiding the fragile two-step completeAuthorization → completeCaptured path.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Payment reconcileDirectToCaptured(UUID paymentId, String pspReference, long capturedCents) {
        Payment payment = paymentRepository.findById(paymentId)
            .orElseThrow(() -> new PaymentNotFoundException(paymentId));
        if (payment.getStatus() == PaymentStatus.CAPTURED) {
            return payment; // idempotent no-op
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
        ledgerService.recordDoubleEntry(saved.getId(), capturedCents,
            "authorization_hold", "merchant_payable", "CAPTURE", saved.getId().toString(),
            "Capture (recovery reconciliation)");
        outboxService.publish("Payment", saved.getId().toString(), "PaymentAuthorized",
            Map.of("orderId", saved.getOrderId(),
                "paymentId", saved.getId(),
                "amountCents", saved.getAmountCents(),
                "currency", saved.getCurrency(),
                "resolvedBy", "stale-pending-recovery"));
        outboxService.publish("Payment", saved.getId().toString(), "PaymentCaptured",
            Map.of("orderId", saved.getOrderId(),
                "paymentId", saved.getId(),
                "amountCents", capturedCents,
                "currency", saved.getCurrency(),
                "resolvedBy", "stale-pending-recovery"));
        auditLogService.log(null,
            "RECOVERY_RECONCILED_TO_CAPTURED",
            "Payment",
            saved.getId().toString(),
            Map.of("orderId", saved.getOrderId(),
                "amountCents", saved.getAmountCents(),
                "capturedCents", capturedCents,
                "currency", saved.getCurrency()));
        return saved;
    }

    // --- Recovery resolution helpers (used by StalePendingPaymentRecoveryJob) ---

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void resolveStaleAuthorizationFailed(UUID paymentId, String reason) {
        paymentRepository.findById(paymentId).ifPresent(p -> {
            if (p.getStatus() != PaymentStatus.AUTHORIZE_PENDING) return;
            p.setStatus(PaymentStatus.FAILED);
            paymentRepository.save(p);
            outboxService.publish("Payment", p.getId().toString(), "PaymentFailed",
                Map.of("orderId", p.getOrderId(),
                    "paymentId", p.getId(),
                    "reason", reason,
                    "resolvedBy", "stale-pending-recovery"));
            auditLogService.log(null,
                "RECOVERY_AUTH_FAILED",
                "Payment",
                p.getId().toString(),
                Map.of("orderId", p.getOrderId(),
                    "reason", reason));
        });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void resolveStaleCaptureFailed(UUID paymentId, String reason) {
        paymentRepository.findById(paymentId).ifPresent(p -> {
            if (p.getStatus() != PaymentStatus.CAPTURE_PENDING) return;
            p.setStatus(PaymentStatus.AUTHORIZED);
            paymentRepository.save(p);
            outboxService.publish("Payment", p.getId().toString(), "PaymentCaptureReverted",
                Map.of("orderId", p.getOrderId(),
                    "paymentId", p.getId(),
                    "reason", reason,
                    "resolvedBy", "stale-pending-recovery"));
            auditLogService.log(null,
                "RECOVERY_CAPTURE_REVERTED",
                "Payment",
                p.getId().toString(),
                Map.of("orderId", p.getOrderId(),
                    "reason", reason));
        });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void resolveStaleVoidFailed(UUID paymentId, String reason) {
        paymentRepository.findById(paymentId).ifPresent(p -> {
            if (p.getStatus() != PaymentStatus.VOID_PENDING) return;
            p.setStatus(PaymentStatus.AUTHORIZED);
            paymentRepository.save(p);
            outboxService.publish("Payment", p.getId().toString(), "PaymentVoidReverted",
                Map.of("orderId", p.getOrderId(),
                    "paymentId", p.getId(),
                    "reason", reason,
                    "resolvedBy", "stale-pending-recovery"));
            auditLogService.log(null,
                "RECOVERY_VOID_REVERTED",
                "Payment",
                p.getId().toString(),
                Map.of("orderId", p.getOrderId(),
                    "reason", reason));
        });
    }

    private String normalizeCurrency(String currency) {
        if (currency == null || currency.isBlank()) {
            return "INR";
        }
        return currency.trim().toUpperCase(Locale.ROOT);
    }

    private void ensurePspReference(Payment payment) {
        if (payment.getPspReference() == null || payment.getPspReference().isBlank()) {
            throw new PaymentGatewayException("PSP reference is missing for payment " + payment.getId());
        }
    }
}
