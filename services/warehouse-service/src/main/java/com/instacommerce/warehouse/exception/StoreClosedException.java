package com.instacommerce.warehouse.exception;

import org.springframework.http.HttpStatus;

public class StoreClosedException extends ApiException {

    public StoreClosedException(String storeId, String reason) {
        super(HttpStatus.SERVICE_UNAVAILABLE, "STORE_CLOSED",
                "Store " + storeId + " is unavailable: " + reason);
    }
}
