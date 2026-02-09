package com.instacommerce.identity.dto.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ChangePasswordRequest(
    @NotBlank
    String currentPassword,
    @NotBlank
    @Size(min = 8, max = 255)
    @Pattern(regexp = "^(?=.*[A-Z])(?=.*\\d).{8,}$", message = "Password must contain at least one uppercase letter and one digit")
    String newPassword
) {
}
