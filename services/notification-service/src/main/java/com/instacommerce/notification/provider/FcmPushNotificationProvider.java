package com.instacommerce.notification.provider;

import com.instacommerce.notification.config.NotificationProperties;
import com.instacommerce.notification.domain.model.NotificationChannel;
import com.instacommerce.notification.service.MaskingUtil;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import java.time.Duration;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;

@Component
@Profile({"prod", "gcp"})
public class FcmPushNotificationProvider implements NotificationProvider {
    private static final Logger logger = LoggerFactory.getLogger(FcmPushNotificationProvider.class);
    private static final String LOCAL_PLACEHOLDER_PREFIX = "local_fcm_placeholder";

    private final RestClient restClient;
    private final NotificationProperties notificationProperties;
    private final Environment environment;

    @Autowired
    public FcmPushNotificationProvider(RestClient.Builder builder,
                                       NotificationProperties notificationProperties,
                                       Environment environment) {
        this.restClient = builder
            .requestFactory(requestFactory())
            .build();
        this.notificationProperties = notificationProperties;
        this.environment = environment;
    }

    public FcmPushNotificationProvider(RestClient.Builder builder,
                                       NotificationProperties notificationProperties) {
        this(builder, notificationProperties, null);
    }

    public FcmPushNotificationProvider(NotificationProperties notificationProperties) {
        this(RestClient.builder(), notificationProperties, null);
    }

    private static SimpleClientHttpRequestFactory requestFactory() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(2));
        factory.setReadTimeout(Duration.ofSeconds(5));
        return factory;
    }

    @Override
    public boolean supports(NotificationChannel channel) {
        return channel == NotificationChannel.PUSH;
    }

    @Override
    @CircuitBreaker(name = "pushProvider", fallbackMethod = "pushFallback")
    public String send(NotificationSendRequest request) {
        NotificationProperties.Fcm fcm = notificationProperties.getProviders().getFcm();
        boolean placeholder = fcm.getAccessToken() != null
            && fcm.getAccessToken().trim().startsWith(LOCAL_PLACEHOLDER_PREFIX);
        if (placeholder) {
            if (isLocalNonProdProfile()) {
                logger.info("Local noop PUSH dispatch to {} title={}",
                    MaskingUtil.maskRecipient(request.recipient(), NotificationChannel.PUSH), request.subject());
                return "local-noop:fcm";
            }
            throw new ProviderPermanentException("FCM local placeholder credentials cannot be used outside local profile");
        }
        if (isBlank(fcm.getProjectId()) || isBlank(fcm.getAccessToken())) {
            throw new ProviderPermanentException("FCM push credentials are not configured");
        }
        String url = !isBlank(fcm.getEndpointUrl())
            ? fcm.getEndpointUrl()
            : "https://fcm.googleapis.com/v1/projects/" + fcm.getProjectId().trim() + "/messages:send";
        Map<String, Object> message = Map.of(
            "message", Map.of(
                "token", request.recipient(),
                "notification", Map.of(
                    "title", request.subject() != null ? request.subject() : "InstaCommerce",
                    "body", request.body() != null ? request.body() : ""
                )
            )
        );
        try {
            ResponseEntity<String> response = restClient.post()
                .uri(url)
                .contentType(MediaType.APPLICATION_JSON)
                .headers(headers -> headers.setBearerAuth(fcm.getAccessToken()))
                .body(message)
                .retrieve()
                .toEntity(String.class);
            if (!response.getStatusCode().is2xxSuccessful()) {
                throw new ProviderTemporaryException("FCM failed with status " + response.getStatusCode());
            }
            return "fcm:" + response.getStatusCode().value();
        } catch (HttpStatusCodeException ex) {
            if (ex.getStatusCode().is4xxClientError()) {
                throw new ProviderPermanentException("FCM rejected push notification: " + ex.getStatusText());
            }
            logger.warn("FCM temporary failure: {}", ex.getStatusText());
            throw new ProviderTemporaryException("FCM temporary failure", ex);
        } catch (Exception ex) {
            throw new ProviderTemporaryException("FCM push request failed", ex);
        }
    }

    private boolean isLocalNonProdProfile() {
        return environment != null
            && environment.acceptsProfiles(Profiles.of("local"))
            && !environment.acceptsProfiles(Profiles.of("prod", "gcp"));
    }

    @SuppressWarnings("unused")
    private String pushFallback(NotificationSendRequest request, Exception ex) {
        logger.warn("notification.circuit_breaker_open provider=push recipient={}",
            MaskingUtil.maskRecipient(request.recipient(), NotificationChannel.PUSH), ex);
        throw new ProviderTemporaryException("Push provider circuit breaker open", ex);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
