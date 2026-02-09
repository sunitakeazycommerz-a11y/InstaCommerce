package com.instacommerce.notification.provider;

import com.instacommerce.notification.domain.model.NotificationChannel;

public record NotificationSendRequest(
    NotificationChannel channel,
    String recipient,
    String subject,
    String body
) {
}
