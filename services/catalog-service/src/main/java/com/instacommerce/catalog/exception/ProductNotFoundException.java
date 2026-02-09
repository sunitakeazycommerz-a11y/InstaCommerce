package com.instacommerce.catalog.exception;

import java.util.UUID;
import org.springframework.http.HttpStatus;

public class ProductNotFoundException extends ApiException {
    public ProductNotFoundException(UUID id) {
        super(HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND", "Product not found: " + id);
    }
}
