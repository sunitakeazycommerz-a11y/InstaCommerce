package com.instacommerce.inventory.exception;

import java.util.UUID;
import org.springframework.http.HttpStatus;

public class ReservationExpiredException extends ApiException {
    public ReservationExpiredException(UUID reservationId) {
        super(HttpStatus.CONFLICT, "RESERVATION_EXPIRED",
            "Reservation expired: " + reservationId);
    }
}
