package com.instacommerce.fulfillment.exception;

import com.instacommerce.fulfillment.domain.model.DeliveryStatus;
import java.util.UUID;
import org.springframework.http.HttpStatus;

public class InvalidDeliveryStateException extends ApiException {
    public InvalidDeliveryStateException(UUID orderId, DeliveryStatus status, String action) {
        super(HttpStatus.CONFLICT, "DELIVERY_STATE_INVALID",
            "Cannot " + action + " when delivery is " + status + " for order " + orderId);
    }
}
