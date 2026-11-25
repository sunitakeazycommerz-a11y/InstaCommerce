package com.instacommerce.payment.service;

import com.instacommerce.payment.domain.model.Payment;
import com.instacommerce.payment.domain.model.PaymentStatus;
import com.instacommerce.payment.dto.mapper.PaymentMapper;
import com.instacommerce.payment.dto.request.AuthorizeRequest;
import com.instacommerce.payment.dto.response.PaymentResponse;
import com.instacommerce.payment.exception.PaymentDeclinedException;
import com.instacommerce.payment.exception.PaymentGatewayException;
import com.instacommerce.payment.exception.PaymentGatewayTransientException;
import com.instacommerce.payment.exception.InvalidCaptureAmountException;
import com.instacommerce.payment.exception.PaymentNotFoundException;
import com.instacommerce.payment.gateway.GatewayAuthRequest;
import com.instacommerce.payment.gateway.GatewayAuthResult;
import com.instacommerce.payment.gateway.GatewayCaptureResult;
import com.instacommerce.payment.gateway.GatewayStatusResult;
import com.instacommerce.payment.gateway.GatewayStatusResult.PspPaymentState;
import com.instacommerce.payment.gateway.GatewayVoidResult;
import com.instacommerce.payment.gateway.PaymentGateway;
import com.instacommerce.payment.repository.PaymentRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PaymentService {
    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);
    private final PaymentRepository paymentRepository;
    private final PaymentGateway paymentGateway;
    private final PaymentTransactionHelper txHelper;
    private final MeterRegistry meterRegistry;

    public PaymentService(PaymentRepository paymentRepository,
                          PaymentGateway paymentGateway,
                          PaymentTransactionHelper txHelper,
                          MeterRegistry meterRegistry) {
        this.paymentRepository = paymentRepository;
        this.paymentGateway = paymentGateway;
        this.txHelper = txHelper;
        this.meterRegistry = meterRegistry;
    }

    /**
     * Step 1: Save AUTHORIZE_PENDING in TX.
     * Step 2: Call PSP outside TX.
     * Step 3: Update to AUTHORIZED (or FAILED) in a new TX.
     */
    public PaymentResponse authorize(AuthorizeRequest request) {
        Timer.Sample sample = Timer.start(meterRegistry);
        String result = "success";
        try {
            PaymentSupport.requireValidCurrency(request.currency());

            String normalizedKey = IdempotencyKeys.normalize(request.idempotencyKey());
            Payment pending = txHelper.savePendingAuthorization(request, normalizedKey);
            Payment target = pending;
            if (target == null) {
                Payment existing = paymentRepository.findByIdempotencyKey(normalizedKey).orElseThrow();
                if (existing.getStatus() != PaymentStatus.AUTHORIZE_PENDING) {
                    return PaymentMapper.toResponse(existing);
                }
                if (existing.getPspReference() != null && !existing.getPspReference().isBlank()) {
                    return PaymentMapper.toResponse(reconcilePendingAuthorization(existing));
                }
                // Previous attempt failed before receiving a PSP reference; retry PSP with the same idempotency key.
                target = existing;
            }

            return executeGatewayAuthorization(target, request, normalizedKey);
        } catch (PaymentGatewayException e) {
            result = "gateway_error";
            throw e;
        } catch (Exception e) {
            result = "failure";
            throw e;
        } finally {
            sample.stop(Timer.builder("payment.operation.duration")
                .tag("operation", "authorize")
                .tag("result", result)
                .register(meterRegistry));
            meterRegistry.counter("payment.operation.total", "operation", "authorize", "result", result).increment();
        }
    }

    private PaymentResponse executeGatewayAuthorization(Payment pending, AuthorizeRequest request, String normalizedKey) {
        String paymentMethod = request.effectivePaymentMethod() != null
            ? request.effectivePaymentMethod()
            : pending.getPaymentMethod();
        GatewayAuthResult gatewayResult;
        try {
            gatewayResult = paymentGateway.authorize(new GatewayAuthRequest(
                pending.getAmountCents(),
                pending.getCurrency(),
                normalizedKey,
                paymentMethod
            ));
        } catch (PaymentGatewayTransientException ex) {
            log.warn("Transient PSP authorization timeout/error for payment {}: {}",
                pending.getId(), ex.getMessage());
            throw ex;
        } catch (Exception ex) {
            String reason = (ex.getMessage() != null && !ex.getMessage().isBlank())
                ? ex.getMessage() : "PSP authorization error";
            txHelper.markAuthorizationFailed(pending.getId(), reason);
            throw ex;
        }

        if (!gatewayResult.success()) {
            String reason = (gatewayResult.declineReason() != null && !gatewayResult.declineReason().isBlank())
                ? gatewayResult.declineReason() : "Authorization declined by PSP";
            txHelper.markAuthorizationFailed(pending.getId(), reason);
            throw new PaymentDeclinedException(gatewayResult.declineReason());
        }

        Payment saved = txHelper.completeAuthorization(pending.getId(), gatewayResult.pspReference());
        return PaymentMapper.toResponse(saved);
    }

    private Payment reconcilePendingAuthorization(Payment existing) {
        try {
            GatewayStatusResult pspState = paymentGateway.getStatus(existing.getPspReference());
            if (pspState == null || pspState.isInFlight()) {
                return existing;
            }
            if (pspState.isAuthorized()) {
                return txHelper.completeAuthorization(existing.getId(), existing.getPspReference());
            }
            if (pspState.isCaptured()) {
                long capturedCents = (pspState.amountCapturedCents() != null && pspState.amountCapturedCents() > 0)
                    ? pspState.amountCapturedCents()
                    : existing.getAmountCents();
                return txHelper.reconcileDirectToCaptured(existing.getId(), existing.getPspReference(), capturedCents);
            }
            if (pspState.state() != PspPaymentState.UNKNOWN) {
                txHelper.resolveStaleAuthorizationFailed(existing.getId(), "psp_status_" + pspState.rawStatus());
                return paymentRepository.findById(existing.getId()).orElse(existing);
            }
        } catch (Exception ex) {
            log.warn("Failed to reconcile AUTHORIZE_PENDING payment {} during idempotent retry: {}",
                existing.getId(), ex.getMessage());
        }
        return existing;
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
        Timer.Sample sample = Timer.start(meterRegistry);
        String result = "success";
        try {
            Payment payment = txHelper.saveCapturePending(paymentId);
            if (payment.getStatus() == PaymentStatus.CAPTURED) {
                return PaymentMapper.toResponse(payment);
            }

            long captureAmount = resolveCaptureAmount(payment, amountCents);
            String normalizedKey = IdempotencyKeys.normalize(idempotencyKey);
            GatewayCaptureResult gatewayResult;
            try {
                gatewayResult = paymentGateway.capture(payment.getPspReference(), captureAmount, normalizedKey);
            } catch (PaymentGatewayTransientException ex) {
                log.warn("Transient PSP capture failure for payment {}: {}", paymentId, ex.getMessage());
                throw ex;
            } catch (Exception ex) {
                txHelper.revertToAuthorized(paymentId);
                throw ex;
            }

            if (!gatewayResult.success()) {
                txHelper.revertToAuthorized(paymentId);
                throw new PaymentGatewayException(gatewayResult.failureReason());
            }

            Payment saved = txHelper.completeCaptured(paymentId, captureAmount);
            return PaymentMapper.toResponse(saved);
        } catch (PaymentGatewayException e) {
            result = "gateway_error";
            throw e;
        } catch (Exception e) {
            result = "failure";
            throw e;
        } finally {
            sample.stop(Timer.builder("payment.operation.duration")
                .tag("operation", "capture")
                .tag("result", result)
                .register(meterRegistry));
            meterRegistry.counter("payment.operation.total", "operation", "capture", "result", result).increment();
        }
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
        Timer.Sample sample = Timer.start(meterRegistry);
        String result = "success";
        try {
            Payment payment = txHelper.saveVoidPending(paymentId);
            if (payment.getStatus() == PaymentStatus.VOIDED) {
                return PaymentMapper.toResponse(payment);
            }

            String normalizedKey = IdempotencyKeys.normalize(idempotencyKey);
            GatewayVoidResult gatewayResult;
            try {
                gatewayResult = paymentGateway.voidAuth(payment.getPspReference(), normalizedKey);
            } catch (PaymentGatewayTransientException ex) {
                log.warn("Transient PSP void failure for payment {}: {}", paymentId, ex.getMessage());
                throw ex;
            } catch (Exception ex) {
                txHelper.revertVoidToAuthorized(paymentId);
                throw ex;
            }

            if (!gatewayResult.success()) {
                txHelper.revertVoidToAuthorized(paymentId);
                throw new PaymentGatewayException(gatewayResult.failureReason());
            }

            Payment saved = txHelper.completeVoided(paymentId);
            return PaymentMapper.toResponse(saved);
        } catch (PaymentGatewayException e) {
            result = "gateway_error";
            throw e;
        } catch (Exception e) {
            result = "failure";
            throw e;
        } finally {
            sample.stop(Timer.builder("payment.operation.duration")
                .tag("operation", "void")
                .tag("result", result)
                .register(meterRegistry));
            meterRegistry.counter("payment.operation.total", "operation", "void", "result", result).increment();
        }
    }

    @Transactional(readOnly = true)
    public PaymentResponse get(UUID paymentId) {
        Payment payment = paymentRepository.findById(paymentId)
            .orElseThrow(() -> new PaymentNotFoundException(paymentId));
        return PaymentMapper.toResponse(payment);
    }

    @Transactional(readOnly = true)
    public PaymentResponse getByReference(String reference) {
        try {
            UUID paymentId = UUID.fromString(reference);
            return paymentRepository.findById(paymentId)
                .or(() -> paymentRepository.findByPspReference(reference))
                .map(PaymentMapper::toResponse)
                .orElseThrow(() -> new PaymentNotFoundException(paymentId));
        } catch (IllegalArgumentException ex) {
            return paymentRepository.findByPspReference(reference)
                .map(PaymentMapper::toResponse)
                .orElseThrow(() -> new PaymentNotFoundException(reference));
        }
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
