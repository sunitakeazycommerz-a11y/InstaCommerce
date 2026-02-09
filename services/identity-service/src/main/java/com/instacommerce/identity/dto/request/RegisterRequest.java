package com.instacommerce.identity.dto.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@JsonIgnoreProperties(ignoreUnknown = true)
public record RegisterRequest(
    @NotBlank
    @Email
    @Size(max = 255)
    String email,
    @NotBlank
    @Size(min = 8, max = 255)
    @Pattern(regexp = "^(?=.*[A-Z])(?=.*\\d).{8,}$",
        message = "Password must be at least 8 characters with 1 uppercase and 1 digit")
    String password
) {
}
