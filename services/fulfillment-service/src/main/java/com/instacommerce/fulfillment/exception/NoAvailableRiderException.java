package com.instacommerce.fulfillment.exception;

import java.util.UUID;
import org.springframework.http.HttpStatus;

public class NoAvailableRiderException extends ApiException {
    public NoAvailableRiderException(UUID storeId) {
        super(HttpStatus.CONFLICT, "NO_AVAILABLE_RIDER", "No available rider for store " + storeId);
    }
}
