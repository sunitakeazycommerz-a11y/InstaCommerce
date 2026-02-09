package com.instacommerce.identity.dto.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@JsonIgnoreProperties(ignoreUnknown = true)
public record LoginRequest(
    @NotBlank
    @Email
    @Size(max = 255)
    String email,
    @NotBlank
    @Size(min = 1, max = 255)
    String password,
    @Size(max = 255)
    String deviceInfo
) {
}
