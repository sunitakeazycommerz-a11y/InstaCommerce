package com.instacommerce.notification.service;

import com.instacommerce.notification.config.InternalServiceAuthInterceptor;
import com.instacommerce.notification.config.NotificationProperties;
import com.instacommerce.notification.domain.model.NotificationChannel;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

@Service
public class UserPreferenceService {
    private final RestTemplate restTemplate;
    private final String baseUrl;
    private final Duration preferenceCacheTtl;
    private final ConcurrentHashMap<UUID, CachedPreferences> preferenceCache = new ConcurrentHashMap<>();

    public UserPreferenceService(RestTemplateBuilder builder, NotificationProperties notificationProperties,
                                 @Value("${internal.service.name:${spring.application.name}}") String serviceName,
                                 @Value("${internal.service.token:dev-internal-token-change-in-prod}") String serviceToken) {
        this.restTemplate = builder
            .setConnectTimeout(Duration.ofSeconds(3))
            .setReadTimeout(Duration.ofSeconds(5))
            .additionalInterceptors(new InternalServiceAuthInterceptor(serviceName, serviceToken))
            .build();
        this.baseUrl = notificationProperties.getIdentity().getBaseUrl();
        this.preferenceCacheTtl = notificationProperties.getIdentity().getPreferenceCacheTtl();
    }

    public Preferences getPreferences(UUID userId) {
        Preferences cached = getCachedPreferences(userId);
        if (cached != null) {
            return cached;
        }
        Preferences preference = fetchPreferences(userId);
        if (preference == null || preference.userId() == null) {
            throw new PreferenceLookupException("Notification preferences unavailable for user " + userId);
        }
        cachePreference(userId, preference);
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

    private Preferences getCachedPreferences(UUID userId) {
        if (preferenceCacheTtl == null || preferenceCacheTtl.isZero() || preferenceCacheTtl.isNegative()) {
            return null;
        }
        CachedPreferences cached = preferenceCache.get(userId);
        if (cached == null) {
            return null;
        }
        if (cached.isExpired(preferenceCacheTtl)) {
            preferenceCache.remove(userId);
            return null;
        }
        return cached.preferences();
    }

    private void cachePreference(UUID userId, Preferences preference) {
        if (preferenceCacheTtl == null || preferenceCacheTtl.isZero() || preferenceCacheTtl.isNegative()) {
            return;
        }
        preferenceCache.put(userId, new CachedPreferences(preference, Instant.now()));
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

    private record CachedPreferences(Preferences preferences, Instant cachedAt) {
        private boolean isExpired(Duration ttl) {
            return cachedAt.plus(ttl).isBefore(Instant.now());
        }
    }
}
