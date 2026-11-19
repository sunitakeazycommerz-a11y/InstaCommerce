package com.instacommerce.catalog.exception;

import org.springframework.http.HttpStatus;

public class DuplicateCategoryException extends ApiException {
    public DuplicateCategoryException(String slug) {
        super(HttpStatus.CONFLICT, "DUPLICATE_CATEGORY", "Category slug already exists: " + slug);
    }
}
