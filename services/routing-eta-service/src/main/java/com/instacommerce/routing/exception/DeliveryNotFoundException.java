package com.instacommerce.routing.exception;

import java.util.UUID;
import org.springframework.http.HttpStatus;

public class DeliveryNotFoundException extends ApiException {

    public DeliveryNotFoundException(UUID deliveryId) {
        super(HttpStatus.NOT_FOUND, "DELIVERY_NOT_FOUND",
            "Delivery not found: " + deliveryId);
    }

    public DeliveryNotFoundException(String message) {
        super(HttpStatus.NOT_FOUND, "DELIVERY_NOT_FOUND", message);
    }
}
