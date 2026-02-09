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
import org.springframework.stereotype.Service;

@Service
public class RefundService {
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
     * Step 1: Create PENDING refund record in TX (with pessimistic lock on payment).
     * Step 2: Call PSP outside TX.
     * Step 3: Update to COMPLETED in new TX.
     */
    public RefundResponse refund(UUID paymentId, RefundRequest request) {
        String idempotencyKey = normalizeIdempotencyKey(request.idempotencyKey());
        if (idempotencyKey != null) {
            Optional<Refund> existing = refundRepository.findByIdempotencyKey(idempotencyKey);
            if (existing.isPresent()) {
                return RefundMapper.toResponse(existing.get());
            }
        }

        String appliedKey = idempotencyKey == null ? generateIdempotencyKey() : idempotencyKey;
        RefundTransactionHelper.RefundPendingResult pending = txHelper.savePendingRefund(paymentId, request, appliedKey);

        GatewayRefundResult result;
        try {
            result = paymentGateway.refund(pending.pspReference(), request.amountCents(), appliedKey);
        } catch (Exception ex) {
            txHelper.markRefundFailed(pending.refundId());
            throw ex;
        }

        if (!result.success()) {
            txHelper.markRefundFailed(pending.refundId());
            throw new PaymentGatewayException(result.failureReason());
        }

        Refund saved = txHelper.completeRefund(pending.refundId(), paymentId, request, result.refundId());
        return RefundMapper.toResponse(saved);
    }

    private String normalizeIdempotencyKey(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        return key.trim();
    }

    private String generateIdempotencyKey() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
