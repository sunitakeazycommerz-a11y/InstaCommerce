package com.instacommerce.riderfleet.dto.response;

import java.time.Instant;
import java.util.UUID;

public record ShiftResponse(
    UUID id,
    UUID riderId,
    Instant shiftStart,
    Instant shiftEnd,
    String status
) {
}
