package com.instacommerce.riderfleet.exception;

import org.springframework.http.HttpStatus;

public class NoAvailableRiderException extends ApiException {
    public NoAvailableRiderException(String storeId) {
        super(HttpStatus.SERVICE_UNAVAILABLE, "NO_AVAILABLE_RIDER",
            "No available rider found for store: " + storeId);
    }
}
