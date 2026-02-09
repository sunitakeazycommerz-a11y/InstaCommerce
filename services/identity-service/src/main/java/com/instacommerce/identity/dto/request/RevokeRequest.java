package com.instacommerce.identity.dto.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;

@JsonIgnoreProperties(ignoreUnknown = true)
public record RevokeRequest(
    @NotBlank
    String refreshToken
) {
}
