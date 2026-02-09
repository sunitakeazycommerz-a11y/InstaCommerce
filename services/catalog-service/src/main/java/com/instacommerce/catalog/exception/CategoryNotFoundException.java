package com.instacommerce.catalog.exception;

import java.util.UUID;
import org.springframework.http.HttpStatus;

public class CategoryNotFoundException extends ApiException {
    public CategoryNotFoundException(UUID id) {
        super(HttpStatus.NOT_FOUND, "CATEGORY_NOT_FOUND", "Category not found: " + id);
    }
}
