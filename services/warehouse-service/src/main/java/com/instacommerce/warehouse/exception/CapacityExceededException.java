package com.instacommerce.warehouse.exception;

import org.springframework.http.HttpStatus;

public class CapacityExceededException extends ApiException {

    public CapacityExceededException(String storeId) {
        super(HttpStatus.TOO_MANY_REQUESTS, "CAPACITY_EXCEEDED",
              "Store " + storeId + " has reached maximum order capacity for this hour");
    }
}
