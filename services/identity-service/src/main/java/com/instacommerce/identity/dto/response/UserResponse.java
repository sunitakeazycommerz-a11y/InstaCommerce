package com.instacommerce.identity.dto.response;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record UserResponse(
    UUID id,
    String email,
    String firstName,
    String lastName,
    String phone,
    List<String> roles,
    String status,
    Instant createdAt
) {
}
