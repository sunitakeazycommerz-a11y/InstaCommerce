package com.instacommerce.payment.dto.mapper;

import com.instacommerce.payment.domain.model.Payment;
import com.instacommerce.payment.dto.response.PaymentResponse;

public final class PaymentMapper {
    private PaymentMapper() {
    }

    public static PaymentResponse toResponse(Payment payment) {
        return new PaymentResponse(
            payment.getId(),
            payment.getStatus().name(),
            payment.getPspReference()
        );
    }
}
