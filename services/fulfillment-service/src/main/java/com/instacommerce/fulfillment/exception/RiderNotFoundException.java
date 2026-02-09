package com.instacommerce.fulfillment.exception;

import java.util.UUID;
import org.springframework.http.HttpStatus;

public class RiderNotFoundException extends ApiException {
    public RiderNotFoundException(UUID riderId) {
        super(HttpStatus.NOT_FOUND, "RIDER_NOT_FOUND", "Rider not found: " + riderId);
    }
}
