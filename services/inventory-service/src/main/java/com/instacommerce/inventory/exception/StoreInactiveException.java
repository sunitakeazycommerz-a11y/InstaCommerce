package com.instacommerce.inventory.exception;

import java.util.UUID;
import org.springframework.http.HttpStatus;

public class StoreInactiveException extends ApiException {
    public StoreInactiveException(UUID storeId, String status) {
        super(HttpStatus.UNPROCESSABLE_ENTITY, "STORE_TEMPORARILY_CLOSED",
              "Dark store " + storeId + " is currently in " + status + " status and cannot accept reservations");
    }
}
