package com.instacommerce.inventory.exception;

import java.util.UUID;
import org.springframework.http.HttpStatus;

public class ProductNotFoundException extends ApiException {
    public ProductNotFoundException(UUID productId, UUID storeId) {
        super(HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND",
            "Stock item not found for product " + productId + " in store " + storeId);
    }
}
