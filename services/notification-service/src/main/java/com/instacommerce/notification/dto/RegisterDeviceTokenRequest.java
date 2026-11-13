package com.instacommerce.notification.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record RegisterDeviceTokenRequest(
    UUID userId,
    @NotBlank @Size(max = 512) String token,
    @Size(max = 20) String platform
) {
}
