package com.instacommerce.catalog.exception;

import org.springframework.http.HttpStatus;

public class InvalidCategoryHierarchyException extends ApiException {
    public InvalidCategoryHierarchyException(String message) {
        super(HttpStatus.BAD_REQUEST, "INVALID_CATEGORY_HIERARCHY", message);
    }
}
