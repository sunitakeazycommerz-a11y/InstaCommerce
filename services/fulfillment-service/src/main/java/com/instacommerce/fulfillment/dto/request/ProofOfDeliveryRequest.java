package com.instacommerce.fulfillment.dto.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ProofOfDeliveryRequest(
    @NotBlank String photoUrl,
    @NotBlank String format,
    @NotNull Integer sizeBytes,
    @NotNull Boolean masked
) {
}
