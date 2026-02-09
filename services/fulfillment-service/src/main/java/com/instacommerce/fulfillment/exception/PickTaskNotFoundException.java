package com.instacommerce.fulfillment.exception;

import java.util.UUID;
import org.springframework.http.HttpStatus;

public class PickTaskNotFoundException extends ApiException {
    public PickTaskNotFoundException(UUID orderId) {
        super(HttpStatus.NOT_FOUND, "PICK_TASK_NOT_FOUND", "Pick task not found for order " + orderId);
    }
}
