package com.instacommerce.riderfleet.exception;

import org.springframework.http.HttpStatus;

public class ShiftNotFoundException extends ApiException {
    public ShiftNotFoundException(String shiftId) {
        super(HttpStatus.NOT_FOUND, "SHIFT_NOT_FOUND", "Rider shift not found: " + shiftId);
    }
}
