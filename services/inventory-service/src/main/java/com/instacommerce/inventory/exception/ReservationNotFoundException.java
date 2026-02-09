package com.instacommerce.inventory.exception;

import java.util.UUID;
import org.springframework.http.HttpStatus;

public class ReservationNotFoundException extends ApiException {
    public ReservationNotFoundException(UUID reservationId) {
        super(HttpStatus.NOT_FOUND, "RESERVATION_NOT_FOUND",
            "Reservation not found: " + reservationId);
    }
}
