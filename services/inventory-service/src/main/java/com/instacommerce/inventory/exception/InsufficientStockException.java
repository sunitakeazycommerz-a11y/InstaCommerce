package com.instacommerce.inventory.exception;

import java.util.UUID;
import org.springframework.http.HttpStatus;

public class InsufficientStockException extends ApiException {
    public InsufficientStockException(UUID productId, int available, int requested) {
        super(HttpStatus.CONFLICT, "INSUFFICIENT_STOCK",
            "Insufficient stock for product " + productId + " (available " + available + ", requested " + requested + ")");
    }
}
