package com.instacommerce.riderfleet.exception;

import org.springframework.http.HttpStatus;

public class RiderNotFoundException extends ApiException {
    public RiderNotFoundException(String riderId) {
        super(HttpStatus.NOT_FOUND, "RIDER_NOT_FOUND", "Rider not found: " + riderId);
    }
}
