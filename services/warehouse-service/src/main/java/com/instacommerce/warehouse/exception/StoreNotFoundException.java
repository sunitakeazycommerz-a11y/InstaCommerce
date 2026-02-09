package com.instacommerce.warehouse.exception;

import java.util.UUID;
import org.springframework.http.HttpStatus;

public class StoreNotFoundException extends ApiException {

    public StoreNotFoundException(UUID storeId) {
        super(HttpStatus.NOT_FOUND, "STORE_NOT_FOUND", "Store not found: " + storeId);
    }
}
