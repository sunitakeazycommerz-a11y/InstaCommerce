package com.instacommerce.payment.dto.mapper;

import com.instacommerce.payment.domain.model.Refund;
import com.instacommerce.payment.dto.response.RefundResponse;

public final class RefundMapper {
    private RefundMapper() {
    }

    public static RefundResponse toResponse(Refund refund) {
        return new RefundResponse(
            refund.getId(),
            refund.getStatus().name(),
            refund.getAmountCents()
        );
    }
}
