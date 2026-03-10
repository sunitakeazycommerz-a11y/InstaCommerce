package com.instacommerce.payment.service;

import com.instacommerce.payment.domain.model.Payment;
import com.instacommerce.payment.domain.model.PaymentStatus;
import com.instacommerce.payment.dto.mapper.PaymentMapper;
import com.instacommerce.payment.dto.request.AuthorizeRequest;
import com.instacommerce.payment.dto.response.PaymentResponse;
import com.instacommerce.payment.exception.PaymentDeclinedException;
import com.instacommerce.payment.exception.PaymentGatewayException;
import com.instacommerce.payment.exception.InvalidCaptureAmountException;
import com.instacommerce.payment.exception.PaymentNotFoundException;
import com.instacommerce.payment.gateway.GatewayAuthRequest;
import com.instacommerce.payment.gateway.GatewayAuthResult;
import com.instacommerce.payment.gateway.GatewayCaptureResult;
import com.instacommerce.payment.gateway.GatewayVoidResult;
import com.instacommerce.payment.gateway.PaymentGateway;
import com.instacommerce.payment.repository.PaymentRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PaymentService {
    private final PaymentRepository paymentRepository;
    private final PaymentGateway paymentGateway;
    private final PaymentTransactionHelper txHelper;

    public PaymentService(PaymentRepository paymentRepository,
                          PaymentGateway paymentGateway,
                          PaymentTransactionHelper txHelper) {
        this.paymentRepository = paymentRepository;
        this.paymentGateway = paymentGateway;
        this.txHelper = txHelper;
    }

    /**
     * Step 1: Save AUTHORIZE_PENDING in TX.
     * Step 2: Call PSP outside TX.
     * Step 3: Update to AUTHORIZED (or FAILED) in a new TX.
     */
    public PaymentResponse authorize(AuthorizeRequest request) {
        String normalizedKey = IdempotencyKeys.normalize(request.idempotencyKey());
        Payment pending = txHelper.savePendingAuthorization(request, normalizedKey);
        if (pending == null) {
            // idempotent hit — already exists
            Payment existing = paymentRepository.findByIdempotencyKey(normalizedKey).orElseThrow();
            return PaymentMapper.toResponse(existing);
        }

        GatewayAuthResult result;
        try {
            result = paymentGateway.authorize(new GatewayAuthRequest(
                request.amountCents(),
                pending.getCurrency(),
                normalizedKey,
                request.paymentMethod()
            ));
        } catch (Exception ex) {
            String reason = (ex.getMessage() != null && !ex.getMessage().isBlank())
                ? ex.getMessage() : "PSP authorization error";
            txHelper.markAuthorizationFailed(pending.getId(), reason);
            throw ex;
        }

        if (!result.success()) {
            String reason = (result.declineReason() != null && !result.declineReason().isBlank())
                ? result.declineReason() : "Authorization declined by PSP";
            txHelper.markAuthorizationFailed(pending.getId(), reason);
            throw new PaymentDeclinedException(result.declineReason());
        }

        Payment saved = txHelper.completeAuthorization(pending.getId(), result.pspReference());
        return PaymentMapper.toResponse(saved);
    }

    /**
     * Step 1: Save CAPTURE_PENDING in TX.
     * Step 2: Call PSP outside TX.
     * Step 3: Update to CAPTURED in a new TX.
     */
    public PaymentResponse capture(UUID paymentId) {
        return capture(paymentId, null, null);
    }

    public PaymentResponse capture(UUID paymentId, Long amountCents) {
        return capture(paymentId, amountCents, null);
    }

    public PaymentResponse capture(UUID paymentId, Long amountCents, String idempotencyKey) {
        Payment payment = txHelper.saveCapturePending(paymentId);
        if (payment.getStatus() == PaymentStatus.CAPTURED) {
            return PaymentMapper.toResponse(payment);
        }

        long captureAmount = resolveCaptureAmount(payment, amountCents);
        String normalizedKey = IdempotencyKeys.normalize(idempotencyKey);
        GatewayCaptureResult result;
        try {
            result = paymentGateway.capture(payment.getPspReference(), captureAmount, normalizedKey);
        } catch (Exception ex) {
            txHelper.revertToAuthorized(paymentId);
            throw ex;
        }

        if (!result.success()) {
            txHelper.revertToAuthorized(paymentId);
            throw new PaymentGatewayException(result.failureReason());
        }

        Payment saved = txHelper.completeCaptured(paymentId, captureAmount);
        return PaymentMapper.toResponse(saved);
    }

    /**
     * Step 1: Save VOID_PENDING in TX.
     * Step 2: Call PSP outside TX.
     * Step 3: Update to VOIDED in a new TX.
     */
    public PaymentResponse voidAuth(UUID paymentId) {
        return voidAuth(paymentId, null);
    }

    public PaymentResponse voidAuth(UUID paymentId, String idempotencyKey) {
        Payment payment = txHelper.saveVoidPending(paymentId);
        if (payment.getStatus() == PaymentStatus.VOIDED) {
            return PaymentMapper.toResponse(payment);
        }

        String normalizedKey = IdempotencyKeys.normalize(idempotencyKey);
        GatewayVoidResult result;
        try {
            result = paymentGateway.voidAuth(payment.getPspReference(), normalizedKey);
        } catch (Exception ex) {
            txHelper.revertVoidToAuthorized(paymentId);
            throw ex;
        }

        if (!result.success()) {
            txHelper.revertVoidToAuthorized(paymentId);
            throw new PaymentGatewayException(result.failureReason());
        }

        Payment saved = txHelper.completeVoided(paymentId);
        return PaymentMapper.toResponse(saved);
    }

    @Transactional(readOnly = true)
    public PaymentResponse get(UUID paymentId) {
        Payment payment = paymentRepository.findById(paymentId)
            .orElseThrow(() -> new PaymentNotFoundException(paymentId));
        return PaymentMapper.toResponse(payment);
    }

    private long resolveCaptureAmount(Payment payment, Long amountCents) {
        long maxAmount = payment.getAmountCents();
        if (amountCents == null) {
            return maxAmount;
        }
        if (amountCents <= 0 || amountCents > maxAmount) {
            throw new InvalidCaptureAmountException(payment.getId(), amountCents, maxAmount);
        }
        return amountCents;
    }

}
