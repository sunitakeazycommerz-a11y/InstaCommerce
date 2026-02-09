package com.instacommerce.payment.exception;

import java.util.UUID;
import org.springframework.http.HttpStatus;

public class InvalidCaptureAmountException extends ApiException {
    public InvalidCaptureAmountException(UUID paymentId, long amountCents, long maxAmountCents) {
        super(HttpStatus.UNPROCESSABLE_ENTITY, "CAPTURE_AMOUNT_INVALID",
            "Capture amount " + amountCents + " exceeds authorized amount " + maxAmountCents
                + " for payment " + paymentId);
    }
}
