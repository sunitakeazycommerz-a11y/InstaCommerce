package com.instacommerce.catalog.exception;

import org.springframework.http.HttpStatus;

public class DuplicateSkuException extends ApiException {
    public DuplicateSkuException(String sku) {
        super(HttpStatus.CONFLICT, "DUPLICATE_SKU", "SKU already exists: " + sku);
    }
}
