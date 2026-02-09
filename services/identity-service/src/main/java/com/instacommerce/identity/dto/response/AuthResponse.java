package com.instacommerce.identity.dto.response;

public record AuthResponse(
    String accessToken,
    String refreshToken,
    long expiresIn,
    String tokenType
) {
}
