package com.instacommerce.payment.exception;

import java.util.UUID;
import org.springframework.http.HttpStatus;

public class PaymentNotFoundException extends ApiException {
    public PaymentNotFoundException(UUID paymentId) {
        super(HttpStatus.NOT_FOUND, "PAYMENT_NOT_FOUND", "Payment not found: " + paymentId);
    }
}
