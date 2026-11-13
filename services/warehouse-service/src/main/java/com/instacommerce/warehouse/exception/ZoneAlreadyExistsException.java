package com.instacommerce.warehouse.exception;

import java.util.UUID;
import org.springframework.http.HttpStatus;

public class ZoneAlreadyExistsException extends ApiException {

    public ZoneAlreadyExistsException(UUID storeId, String pincode) {
        super(HttpStatus.CONFLICT, "ZONE_ALREADY_EXISTS",
              "Zone already exists for store " + storeId + " and pincode " + pincode);
    }
}
