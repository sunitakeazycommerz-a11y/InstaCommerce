package com.instacommerce.inventory.exception;

import com.instacommerce.inventory.domain.model.ReservationStatus;
import java.util.UUID;
import org.springframework.http.HttpStatus;

public class ReservationStateException extends ApiException {
    public ReservationStateException(UUID reservationId, ReservationStatus status, String action) {
        super(HttpStatus.CONFLICT, "RESERVATION_NOT_PENDING",
            "Cannot " + action + " reservation " + reservationId + " in status " + status);
    }
}
