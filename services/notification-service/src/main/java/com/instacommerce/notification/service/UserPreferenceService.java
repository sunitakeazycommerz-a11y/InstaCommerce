package com.instacommerce.notification.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.instacommerce.notification.config.InternalServiceAuthInterceptor;
import com.instacommerce.notification.config.NotificationProperties;
import com.instacommerce.notification.domain.model.NotificationChannel;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

@Service
public class UserPreferenceService {
    private static final Logger logger = LoggerFactory.getLogger(UserPreferenceService.class);
    private static final int MAX_CACHE_ENTRIES = 10_000;
    private static final Duration DEFAULT_CACHE_TTL = Duration.ofMinutes(5);
    private static final LocalTime DEFAULT_QUIET_START = LocalTime.of(22, 0);
    private static final LocalTime DEFAULT_QUIET_END = LocalTime.of(7, 0);

    private final RestTemplate restTemplate;
    private final String baseUrl;
    private final Cache<UUID, Preferences> preferenceCache;
    private final Clock clock;
    private final ObjectProvider<UserPreferenceService> selfProvider;

    public UserPreferenceService(NotificationProperties notificationProperties,
                                 @Value("${internal.service.name:${spring.application.name}}") String serviceName,
                                 @Value("${internal.service.token}") String serviceToken,
                                 Clock clock) {
        this(notificationProperties, serviceName, serviceToken, clock, null);
    }

    @Autowired
    public UserPreferenceService(NotificationProperties notificationProperties,
                                 @Value("${internal.service.name:${spring.application.name}}") String serviceName,
                                 @Value("${internal.service.token}") String serviceToken,
                                 Clock clock,
                                 ObjectProvider<UserPreferenceService> selfProvider) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(3));
        requestFactory.setReadTimeout(Duration.ofSeconds(5));
        this.restTemplate = new RestTemplate(requestFactory);
        this.restTemplate.getInterceptors().add(new InternalServiceAuthInterceptor(serviceName, serviceToken));
        this.baseUrl = notificationProperties.getIdentity().getBaseUrl();
        Duration configuredTtl = notificationProperties.getIdentity().getPreferenceCacheTtl();
        Duration ttl = configuredTtl != null ? configuredTtl : DEFAULT_CACHE_TTL;
        if (ttl.isZero() || ttl.isNegative()) {
            this.preferenceCache = null;
        } else {
            this.preferenceCache = Caffeine.newBuilder()
                .maximumSize(MAX_CACHE_ENTRIES)
                .expireAfterWrite(ttl)
                .build();
        }
        this.clock = clock != null ? clock : Clock.systemUTC();
        this.selfProvider = selfProvider;
    }

    public Preferences getPreferences(UUID userId) {
        Preferences cached = getCachedPreferences(userId);
        if (cached != null) {
            return cached;
        }
        UserPreferenceService proxy = selfProvider != null ? selfProvider.getIfAvailable(() -> this) : this;
        return proxy.fetchPreferences(userId);
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
        if (marketing && isWithinQuietHours(preference)) {
            return false;
        }
        return true;
    }

    public boolean isWithinQuietHours(Preferences preference) {
        if (preference == null) {
            return false;
        }
        boolean quietHoursEnabled = preference.quietHoursEnabled() != null
            ? preference.quietHoursEnabled()
            : (preference.quietHoursStart() != null && preference.quietHoursEnd() != null);
        if (!quietHoursEnabled) {
            return false;
        }
        LocalTime start = preference.quietHoursStart() != null ? preference.quietHoursStart() : DEFAULT_QUIET_START;
        LocalTime end = preference.quietHoursEnd() != null ? preference.quietHoursEnd() : DEFAULT_QUIET_END;
        ZoneId zone = resolveZone(preference.timezone());
        LocalTime now = ZonedDateTime.ofInstant(clock.instant(), zone).toLocalTime();
        if (start.equals(end)) {
            return false;
        }
        if (start.isBefore(end)) {
            return !now.isBefore(start) && now.isBefore(end);
        }
        return !now.isBefore(start) || now.isBefore(end);
    }

    private ZoneId resolveZone(String timezone) {
        if (timezone == null || timezone.isBlank()) {
            return ZoneId.of("UTC");
        }
        try {
            return ZoneId.of(timezone.trim());
        } catch (Exception ex) {
            return ZoneId.of("UTC");
        }
    }

    private Preferences getCachedPreferences(UUID userId) {
        return preferenceCache == null ? null : preferenceCache.getIfPresent(userId);
    }

    private void cachePreference(UUID userId, Preferences preference) {
        if (preferenceCache != null) {
            preferenceCache.put(userId, preference);
        }
    }

    @CircuitBreaker(name = "identityService", fallbackMethod = "fetchPreferencesFallback")
    @Retry(name = "identityService")
    public Preferences fetchPreferences(UUID userId) {
        try {
            Preferences preference = restTemplate.getForObject(
                baseUrl + "/admin/users/" + userId + "/notification-preferences",
                Preferences.class);
            if (preference == null || preference.userId() == null) {
                throw new PreferenceLookupException("Notification preferences unavailable for user " + userId);
            }
            cachePreference(userId, preference);
            return preference;
        } catch (RestClientException ex) {
            throw new PreferenceLookupException("Failed to fetch notification preferences for user " + userId, ex);
        }
    }

    @SuppressWarnings("unused")
    private Preferences fetchPreferencesFallback(UUID userId, Exception ex) {
        logger.warn("Identity service unavailable fetching preferences for user={} ({}); using fail-open transactional defaults",
            userId, ex.getMessage());
        return new Preferences(userId, false, false, false, true);
    }

    public record Preferences(
        UUID userId,
        boolean emailOptOut,
        boolean smsOptOut,
        boolean pushOptOut,
        boolean marketingOptOut,
        Boolean quietHoursEnabled,
        LocalTime quietHoursStart,
        LocalTime quietHoursEnd,
        String timezone
    ) {
        public Preferences(UUID userId, boolean emailOptOut, boolean smsOptOut,
                           boolean pushOptOut, boolean marketingOptOut) {
            this(userId, emailOptOut, smsOptOut, pushOptOut, marketingOptOut, null, null, null, null);
        }
    }
}
