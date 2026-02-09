package com.instacommerce.identity.dto.response;

import java.util.UUID;

public record RegisterResponse(
    UUID userId,
    String message
) {
}
