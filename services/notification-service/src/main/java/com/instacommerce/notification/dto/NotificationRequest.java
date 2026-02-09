package com.instacommerce.notification.dto;

import com.instacommerce.notification.domain.model.NotificationChannel;
import java.util.Map;
import java.util.UUID;

public record NotificationRequest(
    String eventId,
    String eventType,
    UUID userId,
    NotificationChannel channel,
    String templateId,
    String recipient,
    Map<String, Object> variables
) {
}
