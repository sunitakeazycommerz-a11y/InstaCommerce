package com.instacommerce.notification.provider;

import com.instacommerce.notification.config.NotificationProperties;
import com.instacommerce.notification.domain.model.NotificationChannel;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import java.time.Duration;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;

@Component
@Profile("prod")
public class FcmPushNotificationProvider implements NotificationProvider {
    private static final Logger logger = LoggerFactory.getLogger(FcmPushNotificationProvider.class);

    private final RestClient restClient;
    private final NotificationProperties notificationProperties;

    @Autowired
    public FcmPushNotificationProvider(RestClient.Builder builder,
                                       NotificationProperties notificationProperties) {
        this.restClient = builder
            .requestFactory(requestFactory())
            .build();
        this.notificationProperties = notificationProperties;
    }

    public FcmPushNotificationProvider(NotificationProperties notificationProperties) {
        this(RestClient.builder(), notificationProperties);
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

    @SuppressWarnings("unused")
    private String pushFallback(NotificationSendRequest request, Exception ex) {
        logger.warn("notification.circuit_breaker_open provider=push recipient={}", request.recipient(), ex);
        throw new ProviderTemporaryException("Push provider circuit breaker open", ex);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
