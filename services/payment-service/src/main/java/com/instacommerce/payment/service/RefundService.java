package com.instacommerce.payment.service;

import com.instacommerce.payment.domain.model.Refund;
import com.instacommerce.payment.dto.mapper.RefundMapper;
import com.instacommerce.payment.dto.request.RefundRequest;
import com.instacommerce.payment.dto.response.RefundResponse;
import com.instacommerce.payment.exception.PaymentGatewayException;
import com.instacommerce.payment.gateway.GatewayRefundResult;
import com.instacommerce.payment.gateway.PaymentGateway;
import com.instacommerce.payment.repository.RefundRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

@Service
public class RefundService {
    private static final Logger log = LoggerFactory.getLogger(RefundService.class);

    private final RefundRepository refundRepository;
    private final PaymentGateway paymentGateway;
    private final RefundTransactionHelper txHelper;
    private final MeterRegistry meterRegistry;

    public RefundService(RefundRepository refundRepository,
                         PaymentGateway paymentGateway,
                         RefundTransactionHelper txHelper,
                         MeterRegistry meterRegistry) {
        this.refundRepository = refundRepository;
        this.paymentGateway = paymentGateway;
        this.txHelper = txHelper;
        this.meterRegistry = meterRegistry;
    }

    /**
     * Three-step refund flow with idempotency and async PSP handling:
     * Step 1: Create PENDING refund record in TX (with pessimistic lock on payment).
     * Step 2: Call PSP outside TX.
     * Step 3: Update to COMPLETED in new TX.
     */
    public RefundResponse refund(UUID paymentId, RefundRequest request) {
        Timer.Sample sample = Timer.start(meterRegistry);
        String result = "success";
        try {
            String idempotencyKey = IdempotencyKeys.normalize(request.idempotencyKey());
            if (idempotencyKey != null) {
                Optional<Refund> existing = refundRepository.findByIdempotencyKey(idempotencyKey);
                if (existing.isPresent()) {
                    return RefundMapper.toResponse(existing.get());
                }
            }

            String appliedKey = idempotencyKey == null ? generateIdempotencyKey() : idempotencyKey;

            RefundTransactionHelper.RefundPendingResult pending;
            try {
                pending = txHelper.savePendingRefund(paymentId, request, appliedKey);
            } catch (DataIntegrityViolationException ex) {
                Optional<Refund> raceWinner = refundRepository.findByIdempotencyKey(appliedKey);
                if (raceWinner.isPresent()) {
                    log.info("Duplicate refund idempotency key resolved via re-fetch (key={})", appliedKey);
                    return RefundMapper.toResponse(raceWinner.get());
                }
                throw ex;
            }

            GatewayRefundResult gatewayResult;
            try {
                gatewayResult = paymentGateway.refund(pending.pspReference(), request.amountCents(), appliedKey, pending.refundId());
            } catch (Exception ex) {
                String failureReason = (ex.getMessage() != null && !ex.getMessage().isBlank())
                    ? ex.getMessage() : "PSP refund call failed";
                txHelper.markRefundFailed(pending.refundId(), failureReason);
                throw ex;
            }

            if (!gatewayResult.success()) {
                String failureReason = (gatewayResult.failureReason() != null && !gatewayResult.failureReason().isBlank())
                    ? gatewayResult.failureReason() : "PSP refund failed";
                txHelper.markRefundFailed(pending.refundId(), failureReason);
                throw new PaymentGatewayException(failureReason);
            }

            // Persist PSP refund ID immediately so the webhook path can match this refund
            // before synchronous completion runs, closing the race window.
            txHelper.persistPspRefundId(pending.refundId(), gatewayResult.refundId());

            Refund saved = txHelper.completeRefund(pending.refundId(), paymentId, request, gatewayResult.refundId());
            return RefundMapper.toResponse(saved);
        } catch (PaymentGatewayException e) {
            result = "gateway_error";
            throw e;
        } catch (Exception e) {
            result = "failure";
            throw e;
        } finally {
            sample.stop(Timer.builder("payment.refund.duration")
                .tag("result", result)
                .register(meterRegistry));
            meterRegistry.counter("payment.refund.total", "result", result).increment();
        }
    }

    private String generateIdempotencyKey() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
