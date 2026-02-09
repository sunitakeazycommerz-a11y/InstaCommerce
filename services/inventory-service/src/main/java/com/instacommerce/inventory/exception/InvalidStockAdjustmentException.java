package com.instacommerce.inventory.exception;

import org.springframework.http.HttpStatus;

public class InvalidStockAdjustmentException extends ApiException {
    public InvalidStockAdjustmentException(String message) {
        super(HttpStatus.BAD_REQUEST, "INVALID_STOCK_ADJUSTMENT", message);
    }
}
