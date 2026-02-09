package com.instacommerce.fulfillment.exception;

import java.util.UUID;
import org.springframework.http.HttpStatus;

public class PickItemNotFoundException extends ApiException {
    public PickItemNotFoundException(UUID orderId, UUID productId) {
        super(HttpStatus.NOT_FOUND, "PICK_ITEM_NOT_FOUND",
            "Pick item not found for order " + orderId + " and product " + productId);
    }
}
