package com.instacommerce.payment.exception;

import com.instacommerce.payment.domain.model.PaymentStatus;
import java.util.UUID;
import org.springframework.http.HttpStatus;

public class PaymentInvalidStateException extends ApiException {
    public PaymentInvalidStateException(UUID paymentId, PaymentStatus status) {
        super(HttpStatus.UNPROCESSABLE_ENTITY, "PAYMENT_INVALID_STATE",
            "Payment " + paymentId + " is in invalid state: " + status);
    }
}
