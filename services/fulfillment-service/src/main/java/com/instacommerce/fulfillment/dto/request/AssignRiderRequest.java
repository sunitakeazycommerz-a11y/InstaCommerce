package com.instacommerce.fulfillment.dto.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record AssignRiderRequest(
    @NotNull UUID riderId,
    Integer estimatedMinutes
) {
}
