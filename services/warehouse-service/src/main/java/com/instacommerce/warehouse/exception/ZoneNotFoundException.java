package com.instacommerce.warehouse.exception;

import java.util.UUID;
import org.springframework.http.HttpStatus;

public class ZoneNotFoundException extends ApiException {

    public ZoneNotFoundException(UUID zoneId) {
        super(HttpStatus.NOT_FOUND, "ZONE_NOT_FOUND", "Zone not found: " + zoneId);
    }
}
