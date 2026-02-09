package com.instacommerce.payment.exception;

import java.util.UUID;
import org.springframework.http.HttpStatus;

public class RefundExceedsChargeException extends ApiException {
    public RefundExceedsChargeException(UUID paymentId, long requested, long available) {
        super(HttpStatus.UNPROCESSABLE_ENTITY, "REFUND_EXCEEDS_CHARGE",
            "Refund exceeds captured amount for payment " + paymentId
                + " (requested " + requested + ", available " + available + ")");
    }
}
