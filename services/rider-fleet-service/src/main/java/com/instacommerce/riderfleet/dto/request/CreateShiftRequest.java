package com.instacommerce.riderfleet.dto.request;

import jakarta.validation.constraints.NotNull;
import java.time.Instant;

public record CreateShiftRequest(
    @NotNull(message = "shiftStart is required") Instant shiftStart,
    @NotNull(message = "shiftEnd is required") Instant shiftEnd,
    String status
) {
}
