package com.instacommerce.fulfillment.exception;

import java.util.UUID;
import org.springframework.http.HttpStatus;

public class DeliveryNotFoundException extends ApiException {
    public DeliveryNotFoundException(UUID orderId) {
        super(HttpStatus.NOT_FOUND, "DELIVERY_NOT_FOUND", "Delivery not found for order " + orderId);
    }
}
