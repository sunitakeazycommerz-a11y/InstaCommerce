package com.instacommerce.notification.service;

import com.instacommerce.notification.config.InternalServiceAuthInterceptor;
import com.instacommerce.notification.config.NotificationProperties;
import com.instacommerce.notification.domain.model.NotificationChannel;
import java.time.Duration;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

@Service
public class UserPreferenceService {
    private final RestTemplate restTemplate;
    private final String baseUrl;

    public UserPreferenceService(RestTemplateBuilder builder, NotificationProperties notificationProperties,
                                 @Value("${internal.service.name:${spring.application.name}}") String serviceName,
                                 @Value("${internal.service.token:dev-internal-token-change-in-prod}") String serviceToken) {
        this.restTemplate = builder
            .setConnectTimeout(Duration.ofSeconds(3))
            .setReadTimeout(Duration.ofSeconds(5))
            .additionalInterceptors(new InternalServiceAuthInterceptor(serviceName, serviceToken))
            .build();
        this.baseUrl = notificationProperties.getIdentity().getBaseUrl();
    }

    public Preferences getPreferences(UUID userId) {
        Preferences preference = fetchPreferences(userId);
        if (preference == null || preference.userId() == null) {
            throw new PreferenceLookupException("Notification preferences unavailable for user " + userId);
        }
        return preference;
    }

    public boolean allowNotification(Preferences preference, NotificationChannel channel, boolean marketing) {
        if (channel == NotificationChannel.EMAIL && preference.emailOptOut()) {
            return false;
        }
        if (channel == NotificationChannel.SMS && preference.smsOptOut()) {
            return false;
        }
        if (channel == NotificationChannel.PUSH && preference.pushOptOut()) {
            return false;
        }
        if (marketing && preference.marketingOptOut()) {
            return false;
        }
        return true;
    }

    private Preferences fetchPreferences(UUID userId) {
        try {
            return restTemplate.getForObject(
                baseUrl + "/admin/users/" + userId + "/notification-preferences",
                Preferences.class);
        } catch (RestClientException ex) {
            throw new PreferenceLookupException("Failed to fetch notification preferences for user " + userId, ex);
        }
    }

    public record Preferences(
        UUID userId,
        boolean emailOptOut,
        boolean smsOptOut,
        boolean pushOptOut,
        boolean marketingOptOut
    ) {
    }
}
