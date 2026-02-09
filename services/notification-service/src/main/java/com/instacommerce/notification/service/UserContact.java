package com.instacommerce.notification.service;

import java.util.UUID;

public record UserContact(
    UUID userId,
    String email,
    String phone,
    String name,
    String language
) {
}
