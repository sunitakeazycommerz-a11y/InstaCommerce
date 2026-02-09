package com.instacommerce.inventory.dto.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ConfirmRequest(
    @NotNull UUID reservationId
) {
}
