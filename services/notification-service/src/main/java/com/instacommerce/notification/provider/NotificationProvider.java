package com.instacommerce.notification.provider;

import com.instacommerce.notification.domain.model.NotificationChannel;

public interface NotificationProvider {
    boolean supports(NotificationChannel channel);

    String send(NotificationSendRequest request) throws ProviderTemporaryException, ProviderPermanentException;
}
