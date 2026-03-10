package com.instacommerce.payment.service;

import com.instacommerce.payment.domain.model.Refund;
import com.instacommerce.payment.dto.mapper.RefundMapper;
import com.instacommerce.payment.dto.request.RefundRequest;
import com.instacommerce.payment.dto.response.RefundResponse;
import com.instacommerce.payment.exception.PaymentGatewayException;
import com.instacommerce.payment.gateway.GatewayRefundResult;
import com.instacommerce.payment.gateway.PaymentGateway;
import com.instacommerce.payment.repository.RefundRepository;
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

    public RefundService(RefundRepository refundRepository,
                         PaymentGateway paymentGateway,
                         RefundTransactionHelper txHelper) {
        this.refundRepository = refundRepository;
        this.paymentGateway = paymentGateway;
        this.txHelper = txHelper;
    }

    /**
     * Three-step refund flow with idempotency and async PSP handling:
     * Step 1: Create PENDING refund record in TX (with pessimistic lock on payment).
     * Step 2: Call PSP outside TX.
     * Step 3: Update to COMPLETED in new TX.
     */
    public RefundResponse refund(UUID paymentId, RefundRequest request) {
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
            // A concurrent request with the same idempotency key won the insert race.
            // Re-fetch and return the winner's refund instead of surfacing a raw 500.
            Optional<Refund> raceWinner = refundRepository.findByIdempotencyKey(appliedKey);
            if (raceWinner.isPresent()) {
                log.info("Duplicate refund idempotency key resolved via re-fetch (key={})", appliedKey);
                return RefundMapper.toResponse(raceWinner.get());
            }
            // Not an idempotency-key conflict — propagate the original exception.
            throw ex;
        }

        GatewayRefundResult result;
        try {
            result = paymentGateway.refund(pending.pspReference(), request.amountCents(), appliedKey, pending.refundId());
        } catch (Exception ex) {
            txHelper.markRefundFailed(pending.refundId());
            throw ex;
        }

        if (!result.success()) {
            txHelper.markRefundFailed(pending.refundId());
            throw new PaymentGatewayException(result.failureReason());
        }

        // Persist PSP refund ID immediately so the webhook path can match this refund
        // before synchronous completion runs, closing the race window.
        txHelper.persistPspRefundId(pending.refundId(), result.refundId());

        Refund saved = txHelper.completeRefund(pending.refundId(), paymentId, request, result.refundId());
        return RefundMapper.toResponse(saved);
    }

    private String generateIdempotencyKey() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
