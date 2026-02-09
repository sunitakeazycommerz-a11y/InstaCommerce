package com.instacommerce.notification.dto;

import com.instacommerce.notification.domain.model.NotificationStatus;

public record NotificationResult(
    NotificationStatus status,
    String providerRef,
    String errorMessage
) {
}
