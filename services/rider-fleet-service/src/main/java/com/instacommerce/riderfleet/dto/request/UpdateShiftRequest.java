package com.instacommerce.riderfleet.dto.request;

import java.time.Instant;

public record UpdateShiftRequest(
    Instant shiftStart,
    Instant shiftEnd,
    String status
) {
}
