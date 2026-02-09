package com.instacommerce.notification.provider;

import com.instacommerce.notification.domain.model.NotificationChannel;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public class NotificationProviderRegistry {
    private final List<NotificationProvider> providers;

    public NotificationProviderRegistry(List<NotificationProvider> providers) {
        this.providers = providers;
    }

    public Optional<NotificationProvider> findProvider(NotificationChannel channel) {
        return providers.stream()
            .filter(provider -> provider.supports(channel))
            .findFirst();
    }
}
