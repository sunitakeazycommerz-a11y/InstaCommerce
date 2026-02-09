package com.instacommerce.routing.exception;

import com.instacommerce.routing.domain.model.DeliveryStatus;
import org.springframework.http.HttpStatus;

public class InvalidDeliveryStateException extends ApiException {

    public InvalidDeliveryStateException(DeliveryStatus current, DeliveryStatus target) {
        super(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_DELIVERY_STATE",
            "Cannot transition from " + current + " to " + target);
    }
}
