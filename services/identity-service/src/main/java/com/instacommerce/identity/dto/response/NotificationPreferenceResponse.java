package com.instacommerce.identity.dto.response;

import java.util.UUID;

public record NotificationPreferenceResponse(
    UUID userId,
    boolean emailOptOut,
    boolean smsOptOut,
    boolean pushOptOut,
    boolean marketingOptOut
) {
}
