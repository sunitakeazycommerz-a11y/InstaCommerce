package com.instacommerce.fraud.exception;

import org.springframework.http.HttpStatus;

public class BlockedEntityNotFoundException extends ApiException {

    public BlockedEntityNotFoundException(String entityType, String entityValue) {
        super(HttpStatus.NOT_FOUND, "BLOCKED_ENTITY_NOT_FOUND",
                "Blocked entity not found: " + entityType + "/" + entityValue);
    }
}
